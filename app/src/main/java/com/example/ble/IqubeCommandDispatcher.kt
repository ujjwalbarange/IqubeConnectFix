package com.example.ble

import com.example.model.IqubeBleSpec
import com.example.model.QueuedWriteCommand
import com.example.model.WriteCommandStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Dedicated BLE Transmission Controller for characteristic `0x5352` (`Value Handle 0x0022`).
 *
 * Enforces the 100% verified outgoing 20-byte frame formats (every frame ends with `packet[19] = 0xFF`)
 * and serialized FIFO queue spacing (`120 ms` inter-command spacing, `400 ms` between navigation sub-packets):
 * 1. Initial Handshake (`[RU`): `[0x5B, 0x52, 0x55, 0x01, ...ascii("IQUBE_OSS"), ...0x00, 0xFF]`
 * 2. Periodic Clock Sync Heartbeat (`[JD`, every 1.8s): `[0x5B, 0x4A, 0x44, hours, minutes, seconds, 0x01, 0, ..., 0, 0xFF]`
 * 3. Live GPS Locality Text (`0x5B 0x5C` / `createLocationDataArray`): `[0x5B, 0x5C, ...utf8(locality.slice(0, 17)), ...0x00, 0xFF]`
 * 4. Trip Switch & Reset Controller (`0x5A 0xF1` / `createVehicleDataArray` / `buildTripPacket`):
 *    - `0x00` = Switch to Trip A, `0x01` = Switch to Trip B, `0x02` = Reset Active Trip
 *    - Includes mode byte `p[5] = 0x02`, date `p[9..11] = DD/MM/YY`, `p[12] = tripVal`, `p[14] = 0x01`, `p[19] = 0xFF`
 * 5. Dedicated Caller ID Banner (`0x5B 0x43` / `createMobileToClusterArray`) & Call State (`writeCallStateForIQube`):
 *    - `[0x5B, 0x43, ...utf8(firstName.slice(0, 17)), ...0x00, 0xFF]`
 *    - `[0x5B, 0x5D, 0x43, callState (0x00=Ringing, 0x01=Answered, 0x02=Ended), ...0x00, 0xFF]`
 * 6. Turn-by-Turn Navigation 2-Packet Sequence (`sendNavigationData`):
 *    - Packet 1 (`0x5B 0x49`): `[0x5B, 0x49, turnCode, (dist >> 8) & 0xFF, dist & 0xFF, unitByte, ...0x00, 0xFF]`
 *    - 400 ms (`0x190` ms) inter-packet sleep
 *    - Packet 2 (`0x5B 0x4E`): `[0x5B, 0x4E, 0x4E, 0x41, ...utf8(street.slice(0, 15)), ...0x00, 0xFF]`
 *      (or `[0x5B, 0x4E, 0x00, 0x00, ...utf8(dest.slice(0, 15)), ...0x00, 0xFF]` when `turnCode == 0x08`)
 */
class IqubeCommandDispatcher(
    private val scope: CoroutineScope,
    private val performGattWrite: suspend (ByteArray) -> Boolean,
    private val onWriteAckTimeout: () -> Unit
) {
    private val commandIdGenerator = AtomicLong(1L)
    private val queueMutex = Mutex()
    private val pendingQueue = ArrayDeque<QueuedWriteCommand>()

    private var activeCommandId: Long? = null
    private var activeCommandSentAtMs: Long = 0L
    private var lastCommandCompletedAtMs: Long = 0L
    private var ackTimeoutJob: Job? = null

    private val _commandHistory = MutableStateFlow<List<QueuedWriteCommand>>(emptyList())
    val commandHistory: StateFlow<List<QueuedWriteCommand>> = _commandHistory.asStateFlow()

    private val _queueDepth = MutableStateFlow(0)
    val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

    /**
     * Validates that a 20-byte outgoing command frame strictly conforms to the verified
     * TVS iQube SmartXonnect write protocol (including mandatory `Byte 19 = 0xFF` trailer)
     * before transmission to Handle 0x0022 / 0x5352.
     */
    fun validateOutgoingFrame(payload: ByteArray): Result<ByteArray> {
        if (payload.size != IqubeBleSpec.STANDARD_FRAME_SIZE) {
            return Result.failure(
                IllegalArgumentException("Invalid payload length (${payload.size}B). Must be exactly 20 bytes.")
            )
        }

        // Mandatory Frame Trailer Rule: Every 20-byte packet must end with Byte 19 = 0xFF
        if (payload[19] != IqubeBleSpec.FOOTER_DELIMITER) {
            return Result.failure(
                IllegalArgumentException("Frame rejected: Byte 19 must be 0xFF trailer.")
            )
        }

        val b0 = payload[0]
        val b1 = payload[1]
        val b2 = payload[2]

        // Case A: 0x5A binary control frame ([0x5A, 0xF1, 0x00, tripVal, ..., 0xFF])
        if (b0 == IqubeBleSpec.HEADER_TELEMETRY) {
            return Result.success(payload.copyOf())
        }

        // Case B: 0x5B ('[') SmartXonnect frame:
        // - [RU (0x5B, 0x52, 0x55): Initial Handshake & Phone Battery/Signal Relay
        // - [R  (0x5B, 0x52, ...17B UTF-8, 0xFF): Rider Greeting ("Hello <Rider Name>")
        // - [JD (0x5B, 0x4A, 0x44): Periodic Clock & Date Sync Heartbeat
        // - [\  (0x5B, 0x5C, ...17B ASCII, 0xFF): GPS Locality Text
        // - [C  (0x5B, 0x43, ...17B ASCII, 0xFF): Dedicated Caller ID Banner (DATA_ID_CALLER_43)
        // - []C (0x5B, 0x5D, 0x43, callState, ..., 0xFF): Call State (writeCallStateForIQube)
        // - [I  (0x5B, 0x49, turnCode, distHi, distLo, unit, etaHr, etaMin, ..., 0xFF): Navigation Maneuver & Distance
        // - [N  (0x5B, 0x4E, ..., 0xFF): Navigation Assist Street Name / Destination Reached
        // - [b  (0x5B, 0x62, ...17B UTF-8, 0xFF): Music Track Title Banner (SONG_NAME_PACKET)
        if (b0 == IqubeBleSpec.HEADER_ASCII_CMD) {
            val isRiderOrStatus52 = b1 == IqubeBleSpec.DATA_ID_HANDSHAKE_52                  // [RU or [R <riderName>
            val isHeartbeat = b1 == IqubeBleSpec.DATA_ID_HEARTBEAT_4A                        // [J (0x5B 0x4A sendIQubeMobileData)
            val isLocation = b1 == IqubeBleSpec.DATA_ID_LOCATION_5C                          // [\
            val isCaller43 = b1 == IqubeBleSpec.DATA_ID_CALLER_43                            // [C (0x5B 0x43)
            val isCallState5D = b1 == IqubeBleSpec.DATA_ID_CALL_STATE_5D && b2 == 0x43.toByte() // []C
            val isNavManeuver49 = b1 == IqubeBleSpec.DATA_ID_NAV_MANEUVER_49                 // [I (0x5B 0x49)
            val isNavAssist4E = b1 == IqubeBleSpec.DATA_ID_NAV_ASSIST_4E                     // [N (0x5B 0x4E)
            val isNavInstruction4F = b1 == IqubeBleSpec.DATA_ID_NAV_INSTRUCTION_4F           // [O (0x5B 0x4F)
            val isMusicTrack62 = b1 == IqubeBleSpec.SONG_NAME_PACKET                         // [b (0x5B 0x62)

            if (isRiderOrStatus52 || isHeartbeat || isLocation || isCaller43 || isCallState5D || isNavManeuver49 || isNavAssist4E || isNavInstruction4F || isMusicTrack62) {
                return Result.success(payload.copyOf())
            }
            return Result.failure(
                IllegalArgumentException("Unrecognized 0x5B header. Allowed: [RU/[R (5B 52), [J (5B 4A), [\\ (5B 5C), [C (5B 43), []C (5B 5D 43), [I (5B 49), [N (5B 4E), [O (5B 4F), or [b (5B 62).")
            )
        }

        return Result.failure(
            IllegalArgumentException("Frame rejected: Byte 0 must be 0x5A ('Z') or 0x5B ('[').")
        )
    }

    /**
     * 1A. Initial Session Handshake (`[RU`):
     *    `[0x5B, 0x52, 0x55, 0x01, ...ascii("IQUBE_OSS"), ...0x00, 0xFF]`
     */
    fun buildInitialHandshakeFrame(riderAlias: String = "IQUBE_OSS"): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = 0x5B // '['
        frame[1] = 0x52 // 'R'
        frame[2] = 0x55 // 'U'
        frame[3] = 0x01 // Protocol version
        val ascii = riderAlias.toByteArray(StandardCharsets.US_ASCII)
        val len = minOf(ascii.size, 15)
        System.arraycopy(ascii, 0, frame, 4, len)
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * 1B. Phone Battery & Network Status Bar Relay (`0x5B 0x52 0x55` / `buildPhoneStatusPacket`):
     *     Maps `batteryPct` (0..100%) to `batteryBars` (0..4 bars: e.g. 20% -> 1 bar).
     */
    fun buildPhoneStatusPacket(batteryPct: Int = 85, signalBars: Int = 4): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        val clampedPct = batteryPct.coerceIn(0, 100)
        val batteryBars = IqubeBleSpec.computePhoneBatteryBars(clampedPct)
        p[0] = 0x5B // '['
        p[1] = 0x52 // 'R'
        p[2] = 0x55 // 'U'
        p[3] = (clampedPct and 0xFF).toByte()                  // Phone battery % (0..100)
        p[4] = (batteryBars and 0xFF).toByte()                 // Phone battery bars (0..4 bars, e.g. 1 bar at 20%)
        p[5] = 0x01                                            // Bluetooth connected & network active
        p[6] = (signalBars.coerceIn(0, 4) and 0xFF).toByte()   // Cellular signal bars (0..4)
        p[19] = IqubeBleSpec.FOOTER_DELIMITER                  // 0xFF
        return p
    }

    /**
     * 2. Clock, Status Tray & Missed Call Synchronization (`0x5B 0x4A` — `BlePacketsProvider.sendIQubeMobileData` at `0f97b8`):
     *    Verified byte-by-byte from `classes12.dex`:
     *    - `Byte [0..1] = [0x5B, 0x4A]` (`[J`)
     *    - `Byte [2] = ((signalBars & 0x0F) << 4) | (batteryBars & 0x0F)`
     *      (High nibble = Network Signal Bars 0..4, Low nibble = Phone Battery Bars 0..4; e.g. 4 sig + 1 batt at 20% = `0x41`)
     *    - `Byte [3] = 0x78` (120)
     *    - `Byte [4] = 0x81.toByte()` (-127)
     *    - `Byte [5] = 0x02`
     *    - `Byte [6] = hour12` (1..12 in 12-hour format from `getCurrentHour(calendar)`)
     *    - `Byte [7] = minute` (0..59)
     *    - `Byte [8] = second` (0..59)
     *    - `Byte [9] = isAm` (`0x01` = AM, `0x00` = PM)
     *    - `Byte [10] = missedCallsCount` (`0x00` normally; `>0` lights up the cluster's Missed Call icon!)
     *    - `Byte [11] = 0x00` (`NETWORK_TYPE`)
     *    - `Byte [12] = activeCallState` (`0x00` = Idle, `0x01` = Call Active / Ringing)
     *    - `Byte [13..18] = 0x00`
     *    - `Byte [19] = 0xFF`
     */
    fun buildClockSyncPacket(
        cal: Calendar = Calendar.getInstance(),
        batteryPct: Int? = null,
        signalBars: Int = 4,
        missedCallsCount: Int = 0,
        activeCallState: Int = 0
    ): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        val battBars = if (batteryPct != null) {
            IqubeBleSpec.computePhoneBatteryBars(batteryPct)
        } else {
            4
        }
        val sigBars = signalBars.coerceIn(0, 4)
        val rawHour12 = cal.get(Calendar.HOUR)
        val hour12 = if (rawHour12 == 0) 12 else rawHour12
        val isAmByte: Byte = if (cal.get(Calendar.AM_PM) == Calendar.AM) 0x01 else 0x00

        p[0] = 0x5B // '['
        p[1] = 0x4A // 'J'
        // High nibble = Network Signal Bars (0..4), Low nibble = Phone Battery Bars (0..4)
        p[2] = (((sigBars and 0x0F) shl 4) or (battBars and 0x0F)).toByte()
        p[3] = 0x78.toByte()                                       // 120 (fixed U347 protocol byte)
        p[4] = 0x81.toByte()                                       // -127 (fixed U347 RSSI baseline byte)
        p[5] = 0x02                                                // 2 (fixed U347 mode byte)
        p[6] = (hour12 and 0xFF).toByte()                          // 12-hour clock hour (1..12)
        p[7] = (cal.get(Calendar.MINUTE) and 0xFF).toByte()        // Minutes (0..59)
        p[8] = (cal.get(Calendar.SECOND) and 0xFF).toByte()        // Seconds (0..59)
        p[9] = isAmByte                                            // 0x01 = AM, 0x00 = PM
        p[10] = (missedCallsCount.coerceIn(0, 99) and 0xFF).toByte() // Missed calls count (0 = icon OFF, >0 = icon ON)
        p[11] = 0x00                                               // NETWORK_TYPE (0)
        p[12] = (activeCallState.coerceIn(0, 2) and 0xFF).toByte() // Call state (0 = no active call, 1 = call active)
        p[19] = IqubeBleSpec.FOOTER_DELIMITER                      // 0xFF
        return p
    }

    fun buildHeartbeatSyncFrame(): ByteArray = buildClockSyncPacket()

    /**
     * 3. Live GPS Locality / Simple Banner Text (`0x5B 0x5C` / `createLocationDataArray`):
     *    `[0x5B, 0x5C, ...utf8(locality.slice(0, 17)), ...paddingZeros, 0xFF]`
     */
    fun buildClusterLocationPacket(locationName: String): ByteArray {
        val packet = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE) // zero-filled (0x00 padded)
        packet[0] = 0x5B // '['
        packet[1] = 0x5C // '\' Location Frame ID
        val cleanAscii = locationName
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
            .take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
        val asciiBytes = cleanAscii.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(asciiBytes, 0, packet, 2, minOf(asciiBytes.size, IqubeBleSpec.MAX_LOCATION_ASCII_BYTES))
        packet[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return packet
    }

    /**
     * 4. Trip Switch, Reset & Date Controller (`0x5A 0xF1` / `BluetoothUtil.createVehicleDataArray` at `381524`):
     *    - `tripVal = 0x00`: Switch to Trip A + Sync Date
     *    - `tripVal = 0x01`: Switch to Trip B + Sync Date
     *    - `tripVal = 0x02`: Reset Active Trip + Sync Date
     *    Matches `createVehicleDataArray` in `classes7.dex`: `p[6..8] = 0x00`, `p[9..11] = DD/MM/YY`, `p[12] = tripVal`, `p[14] = 0x01`.
     */
    fun buildTripControlFrame(tripVal: Byte, calendar: Calendar = Calendar.getInstance()): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        p[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        p[1] = 0xF1.toByte()                 // Trip & Vehicle Date Opcode
        p[2] = 0x00
        p[3] = tripVal                       // 0x00=Trip A, 0x01=Trip B, 0x02=Reset Active
        p[4] = 0x00
        p[5] = 0x02                          // Mode control
        p[6] = 0x00
        p[7] = 0x00
        p[8] = 0x00
        p[9] = calendar.get(Calendar.DAY_OF_MONTH).toByte()           // Day (1..31)
        p[10] = (calendar.get(Calendar.MONTH) + 1).toByte()           // Month (1..12)
        p[11] = (calendar.get(Calendar.YEAR) % 100).toByte()          // 2-digit Year (e.g. 26)
        p[12] = tripVal
        p[13] = 0x00
        p[14] = 0x01
        p[15] = 0x00
        p[16] = 0x00
        p[17] = 0x00
        p[18] = 0x00
        p[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return p
    }

    /**
     * 5A. Dedicated Incoming Caller ID (`0x5B 0x43` / `createMobileToClusterArray`):
     *     Strictly used ONLY for Incoming Call caller names (`DATA_ID_CALLER_43`), because the cluster
     *     renders `0x5B 0x43` with the "Incoming" call tag.
     */
    fun buildCallerPacket0x43(fullOrFirstName: String, extractFirstWordOnly: Boolean = true): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        p[0] = 0x5B // '['
        p[1] = IqubeBleSpec.DATA_ID_CALLER_43 // 0x43 ('C') — DATA_ID_CALLER_43
        val cleanText = if (extractFirstWordOnly) {
            fullOrFirstName.trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.replace(Regex("[^\\x20-\\x7E]"), "")
                ?.ifBlank { "Caller" } ?: "Caller"
        } else {
            fullOrFirstName.replace(Regex("[^\\x20-\\x7E]"), "").trim().ifBlank { "Caller" }
        }
        val nameBytes = cleanText.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES).toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(nameBytes, 0, p, 2, minOf(nameBytes.size, IqubeBleSpec.MAX_LOCATION_ASCII_BYTES))
        p[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return p
    }

    /**
     * 5B. Call State Frame (`writeCallStateForIQube`):
     *     - `0x00` = Incoming Call (Ringing)
     *     - `0x01` = Answered / Ongoing Call
     *     - `0x02` = Call Ended
     *     - `0x03` = Missed Call
     *     - `0x04` = Outgoing Call
     */
    fun buildCallStatePacket(callStateByte: Byte, firstName: String = ""): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = 0x5B // '['
        frame[1] = IqubeBleSpec.DATA_ID_CALL_STATE_5D // 0x5D (']')
        frame[2] = 0x43 // 'C'
        frame[3] = callStateByte
        if (firstName.isNotBlank()) {
            val cleanAscii = firstName.replace(Regex("[^\\x20-\\x7E]"), "").trim().take(15)
            val asciiBytes = cleanAscii.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(asciiBytes, 0, frame, 4, minOf(asciiBytes.size, 15))
        }
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return frame
    }

    /**
     * 6A. Bytecode-Verified Turn-by-Turn Navigation Instruction Packet (`pair.second` in `BleNavigationSendData$Companion.byteArrayForNavigation` at `0dcc78`):
     *     - `Byte [0..1] = [0x5B, 0x4F]` (`[O`)
     *     - `Byte [2..18]` = 17-byte UTF-8 maneuver instruction (0x00 padded)
     *     - `Byte [19] = 0xFF`
     */
    fun buildNavigationInstructionPacket0x4F(instructionText: String): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        p[0] = 0x5B // '['
        p[1] = IqubeBleSpec.DATA_ID_NAV_INSTRUCTION_4F // 0x4F ('O')
        val cleanText = instructionText
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
            .take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
        val bytes = cleanText.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(bytes, 0, p, 2, minOf(bytes.size, IqubeBleSpec.MAX_LOCATION_ASCII_BYTES))
        p[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return p
    }

    /**
     * 6B. Bytecode-Verified Turn-by-Turn Navigation Control & Telemetry Frame (`pair.first` in `BleNavigationSendData$Companion.byteArrayForNavigation` at `0dcc78`):
     *     Verified byte-by-byte from `classes12.dex`:
     *     - `Byte [0]`: `headerByte` (`0x5A` = `'Z'` or `0x5B` = `'['`)
     *     - `Byte [1]`: `0x4E` (`'N'`)
     *     - `Byte [2..3]` (`distanceBytes`): 16-bit Big-Endian distance to next turn in meters (`(distMeters >> 8) & 0xFF`, `distMeters & 0xFF`)
     *     - `Byte [4..5]` (`remainingTime`): 16-bit Big-Endian total ETA in minutes (`(etaMinutes >> 8) & 0xFF`, `etaMinutes & 0xFF`)
     *     - `Byte [6..8]` (`remainingDistance`): 24-bit Big-Endian remaining route distance in meters
     *     - `Byte [9]` (`pictogram`): `turnCode` from `TurnTypeConstants` (`0x00` Left, `0x03` Right, `0x06` U-Turn, `0x07` Straight, `0x08` Dest Reached, etc.)
     *     - `Byte [10]` (`oneString`): `0x01`
     *     - `Byte [11]` (`navOnOFF`): `0x01` (Navigation ON) or `0x00` (Navigation OFF)
     *     - `Byte [12..18]`: `0x00`
     *     - `Byte [19]`: `0xFF`
     */
    fun buildNavigationControlPacket0x4E(
        turnCode: Int,
        distanceMeters: Int,
        etaMinutes: Int = 12,
        totalRemainingDistanceMeters: Int = max(distanceMeters, 500),
        navActive: Boolean = true,
        headerByte: Byte = IqubeBleSpec.HEADER_TELEMETRY // 0x5A ('Z') or 0x5B ('[')
    ): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        val clampedDist = distanceMeters.coerceIn(0, 65535)
        val clampedEtaMin = etaMinutes.coerceIn(0, 1439)
        val clampedRemDist = totalRemainingDistanceMeters.coerceIn(0, 0xFFFFFF)

        p[0] = headerByte
        p[1] = IqubeBleSpec.DATA_ID_NAV_ASSIST_4E // 0x4E ('N')
        // Byte [2..3]: distanceBytes (uint16 BE distance to next turn in meters)
        p[2] = ((clampedDist shr 8) and 0xFF).toByte()
        p[3] = (clampedDist and 0xFF).toByte()
        // Byte [4..5]: remainingTime (uint16 BE total ETA in minutes)
        p[4] = ((clampedEtaMin shr 8) and 0xFF).toByte()
        p[5] = (clampedEtaMin and 0xFF).toByte()
        // Byte [6..8]: remainingDistance (uint24 BE total remaining route distance in meters)
        p[6] = ((clampedRemDist shr 16) and 0xFF).toByte()
        p[7] = ((clampedRemDist shr 8) and 0xFF).toByte()
        p[8] = (clampedRemDist and 0xFF).toByte()
        // Byte [9]: pictogram (turnCode 0x00..0x48)
        p[9] = (turnCode and 0xFF).toByte()
        // Byte [10]: oneString (0x01)
        p[10] = 0x01
        // Byte [11]: navOnOFF (0x01 = active, 0x00 = ended)
        p[11] = if (navActive) 0x01 else 0x00
        // Byte [12..18]: 0x00
        p[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return p
    }

    /**
     * Legacy helper alias for Packet 1 (`0x5A 0x4E` binary navigation frame)
     */
    fun buildNavigationManeuverPacket1(
        turnCode: Int,
        distanceValue: Int,
        unitByte: Int = 0x01,
        etaMinutes: Int = 12
    ): ByteArray {
        return buildNavigationControlPacket0x4E(
            turnCode = turnCode,
            distanceMeters = distanceValue,
            etaMinutes = etaMinutes,
            totalRemainingDistanceMeters = max(distanceValue, 500),
            navActive = true,
            headerByte = IqubeBleSpec.HEADER_TELEMETRY // 0x5A 0x4E
        )
    }

    /**
     * Legacy helper alias for Packet 2 (`0x5B 0x4E` binary navigation frame)
     */
    fun buildNavigationStreetPacket2(
        turnCode: Int,
        streetOrDestName: String = "",
        distanceMeters: Int = 250,
        etaMinutes: Int = 12
    ): ByteArray {
        return buildNavigationControlPacket0x4E(
            turnCode = turnCode,
            distanceMeters = distanceMeters,
            etaMinutes = etaMinutes,
            totalRemainingDistanceMeters = max(distanceMeters, 500),
            navActive = true,
            headerByte = IqubeBleSpec.HEADER_ASCII_CMD // 0x5B 0x4E
        )
    }

    /**
     * 6C. End / Clear Navigation Frame (`NavigationViewModel.sendEndNavigationArray` at `520424` in `classes3.dex`):
     *     `[0x5A, 0x4E, 0x00, ..., 0x00, 0xFF]` (sets `navOnOFF = 0x00`).
     */
    fun buildEndNavigationPacket(headerByte: Byte = IqubeBleSpec.HEADER_TELEMETRY): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        p[0] = headerByte
        p[1] = IqubeBleSpec.DATA_ID_NAV_ASSIST_4E // 0x4E ('N')
        p[19] = IqubeBleSpec.FOOTER_DELIMITER     // 0xFF
        return p
    }

    /**
     * 7. Music Track Title Banner (`SONG_NAME_PACKET = 0x62` / `0x5B 0x62` — `buildMusicTrackPacket`):
     *    `[0x5B, 0x62, ...utf8(songTitle.slice(0, 17)), ...0x00, 0xFF]`
     */
    fun buildMusicTrackPacket(songTitle: String): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        p[0] = 0x5B // '['
        p[1] = IqubeBleSpec.SONG_NAME_PACKET // 0x62 ('b')
        val cleanSong = songTitle
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
            .take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
        val bytes = cleanSong.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(bytes, 0, p, 2, minOf(bytes.size, IqubeBleSpec.MAX_LOCATION_ASCII_BYTES))
        p[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return p
    }

    /**
     * 8. Editable Rider Greeting Name (`createRiderNameArray` / `0x5B 0x52` — `buildRiderNamePacket`):
     *    `[0x5B, 0x52, ...utf8(riderName.slice(0, 17)), ...0x00, 0xFF]` ("Hello <Rider Name>")
     */
    fun buildRiderNamePacket(riderName: String): ByteArray {
        val p = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        p[0] = 0x5B // '['
        p[1] = IqubeBleSpec.DATA_ID_HANDSHAKE_52 // 0x52 ('R')
        val cleanRider = riderName
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
            .ifEmpty { "Rider" }
            .take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
        val bytes = cleanRider.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(bytes, 0, p, 2, minOf(bytes.size, IqubeBleSpec.MAX_LOCATION_ASCII_BYTES))
        p[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return p
    }

    fun enqueueCommand(label: String, rawPayload: ByteArray): Result<QueuedWriteCommand> {
        val now = System.currentTimeMillis()
        val id = commandIdGenerator.getAndIncrement()
        val validation = validateOutgoingFrame(rawPayload)

        if (validation.isFailure) {
            val rejected = QueuedWriteCommand(
                id = id,
                label = label,
                payload = rawPayload.copyOf(),
                enqueuedAtMs = now,
                status = WriteCommandStatus.REJECTED_FRAMING,
                errorDetail = validation.exceptionOrNull()?.message
            )
            recordCommandUpdate(rejected)
            return Result.failure(validation.exceptionOrNull() ?: IllegalArgumentException("Invalid frame"))
        }

        val command = QueuedWriteCommand(
            id = id,
            label = label,
            payload = validation.getOrThrow(),
            enqueuedAtMs = now,
            status = WriteCommandStatus.QUEUED
        )

        scope.launch {
            queueMutex.withLock {
                pendingQueue.addLast(command)
                _queueDepth.value = pendingQueue.size
                recordCommandUpdate(command)
            }
            processNextInQueue()
        }

        return Result.success(command)
    }

    private fun processNextInQueue() {
        scope.launch {
            val nextCmd: QueuedWriteCommand?
            val waitBeforeSendMs: Long

            queueMutex.withLock {
                if (activeCommandId != null || pendingQueue.isEmpty()) {
                    return@launch
                }
                val cmd = pendingQueue.removeFirst()
                _queueDepth.value = pendingQueue.size
                activeCommandId = cmd.id
                val elapsedSinceLast = System.currentTimeMillis() - lastCommandCompletedAtMs
                waitBeforeSendMs = if (lastCommandCompletedAtMs > 0L) {
                    max(0L, IqubeBleSpec.INTER_COMMAND_SPACING_MS - elapsedSinceLast)
                } else 0L
                nextCmd = cmd.copy(status = WriteCommandStatus.AWAITING_ACK)
            }

            if (nextCmd != null) {
                if (waitBeforeSendMs > 0L) {
                    delay(waitBeforeSendMs)
                }
                activeCommandSentAtMs = System.currentTimeMillis()
                recordCommandUpdate(nextCmd)

                ackTimeoutJob?.cancel()
                ackTimeoutJob = scope.launch {
                    delay(IqubeBleSpec.WRITE_ACK_TIMEOUT_MS)
                    handleWriteTimeout(nextCmd.id)
                }

                val writeInitiated = performGattWrite(nextCmd.payload)
                if (!writeInitiated) {
                    ackTimeoutJob?.cancel()
                    queueMutex.withLock {
                        if (activeCommandId == nextCmd.id) {
                            activeCommandId = null
                            lastCommandCompletedAtMs = System.currentTimeMillis()
                        }
                    }
                    recordCommandUpdate(
                        nextCmd.copy(
                            status = WriteCommandStatus.DISCONNECTED_ERROR,
                            errorDetail = "GATT write characteristic unavailable"
                        )
                    )
                    processNextInQueue()
                }
            }
        }
    }

    fun onWriteResponseReceived(gattStatus: Int = 0) {
        scope.launch {
            val completedId: Long?
            val latencyMs: Long
            queueMutex.withLock {
                completedId = activeCommandId
                val now = System.currentTimeMillis()
                latencyMs = (now - activeCommandSentAtMs).coerceAtLeast(1L)
                lastCommandCompletedAtMs = now
                activeCommandId = null
                ackTimeoutJob?.cancel()
            }

            if (completedId != null) {
                _commandHistory.update { list ->
                    list.map { item ->
                        if (item.id == completedId) {
                            if (gattStatus == 0) {
                                item.copy(status = WriteCommandStatus.ACK_RECEIVED, ackLatencyMs = latencyMs)
                            } else {
                                item.copy(
                                    status = WriteCommandStatus.DISCONNECTED_ERROR,
                                    ackLatencyMs = latencyMs,
                                    errorDetail = "GATT status 0x${Integer.toHexString(gattStatus)}"
                                )
                            }
                        } else item
                    }
                }
            }
            processNextInQueue()
        }
    }

    private suspend fun handleWriteTimeout(timedOutId: Long) {
        var didTimeout = false
        queueMutex.withLock {
            if (activeCommandId == timedOutId) {
                activeCommandId = null
                lastCommandCompletedAtMs = System.currentTimeMillis()
                didTimeout = true
            }
        }
        if (didTimeout) {
            _commandHistory.update { list ->
                list.map { item ->
                    if (item.id == timedOutId) {
                        item.copy(
                            status = WriteCommandStatus.TIMEOUT_500MS,
                            ackLatencyMs = IqubeBleSpec.WRITE_ACK_TIMEOUT_MS,
                            errorDetail = "ATT Write Response (0x13) timed out >500ms (Packet #1676 condition)"
                        )
                    } else item
                }
            }
            onWriteAckTimeout()
            processNextInQueue()
        }
    }

    fun clearPendingQueueOnDisconnect() {
        scope.launch {
            queueMutex.withLock {
                ackTimeoutJob?.cancel()
                activeCommandId = null
                pendingQueue.clear()
                _queueDepth.value = 0
            }
        }
    }

    private fun recordCommandUpdate(cmd: QueuedWriteCommand) {
        _commandHistory.update { current ->
            val existingIdx = current.indexOfFirst { it.id == cmd.id }
            val updated = if (existingIdx >= 0) {
                current.toMutableList().apply { set(existingIdx, cmd) }
            } else {
                listOf(cmd) + current
            }
            updated.take(40)
        }
    }
}
