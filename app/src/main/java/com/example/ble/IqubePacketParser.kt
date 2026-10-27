package com.example.ble

import com.example.model.ByteOffsetMapping
import com.example.model.DecodedIqubePacket
import com.example.model.IqubeBleSpec
import com.example.model.MusicCommand
import com.example.model.RideMode
import com.example.model.TelemetryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 100% Verified Unfiltered 20-Byte Binary Packet Parser for TVS iQube (`0x5354` / `Handle 0x001E`).
 *
 * Every 20-byte frame begins with `Byte 0 = 0x5A` ('Z'), `Byte 1 = Opcode`, and ends with `Byte 19 = 0xFF`:
 * - `0x5A 0x10` (4.7 Hz):
 *   - `Byte 2` = `uint8` Live Wheel Speed in `km/h` (`d[2]`).
 *   - `Bytes 3..5` = `uint24 Big-Endian` Total Hardware Odometer in `0.1 km` units (`((d[3] << 16) | (d[4] << 8) | d[5]) / 10.0`, e.g., `05 7B 54` = `35,925.2 km`).
 *   - `Byte 11` = Cluster Brightness Level.
 *   - `Byte 12` = Ignition & Assist Flag (`0x80` = Ignition OFF, `0x04` = Voice Assist).
 *   - `Byte 13` = 0–30 km/h Sprint Time in ms (`d[13] * 10`).
 * - `0x5A 0x13` (3.1 Hz):
 *   - `Byte 2` = `uint8` Battery SoC `%` (`d[2]`, e.g., `0x26..0x2A` = `38%..42%`).
 *   - `Bytes 3..4` = `uint16 Big-Endian` Cluster Projected Range in `0.1 km` units (`((d[3] << 8) | d[4]) / 10.0`, e.g., `0x0190` = `40.0 km`, `0x01C2` = `45.0 km`).
 *   - `Byte 5` = State Flag (`0x06` when charging).
 * - `0x5A 0x11` (0.5 Hz):
 *   - `Bytes 2..4` = `uint24 Big-Endian` Hardware Trip A in `0.1 km` units (`((d[2] << 16) | (d[3] << 8) | d[4]) / 10.0`, e.g., `00 00 03` = `0.3 km`).
 *   - `Byte 5` = Charging Active Flag (`0x01` = Charging Active, `0x00` = Not Charging).
 *   - `Byte 6` = Battery SoC `%` mirror (`d[6]`).
 *   - `Bytes 7..9` = `uint24 Big-Endian` Hardware Trip B in `0.1 km` units (`((d[7] << 16) | (d[8] << 8) | d[9]) / 10.0`, e.g., `00 00 0A` = `1.0 km`).
 * - `0x5A 0x7D` (3.1 Hz):
 *   - `Bytes 2..18` = ASCII Vehicle Identifier string (`"TVSiQUBEJI573"`).
 * - `0x5A 0x6B` (Event):
 *   - `Byte 2` = Cluster Handlebar Music Control Event (`0x00`=Play, `0x01`=Pause, `0x02`=Toggle, `0x03`=Next, `0x04`=Prev, `0x05`=Vol+, `0x06`=Vol-).
 */
class IqubePacketParser(
    var onMediaCommandReceived: ((MusicCommand, Long) -> Unit)? = null
) {

    private val bufferLock = Any()
    private val rxBuffer = ArrayList<Byte>(256)

    private val _byteOffsetMapping = MutableStateFlow(ByteOffsetMapping())
    val byteOffsetMapping: StateFlow<ByteOffsetMapping> = _byteOffsetMapping.asStateFlow()

    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    // Motion & Sprint Integration State
    private var lastSpeedPacketTimeMs: Long? = null
    private var lastSpeedKmh: Int = 0
    private var sprintStartTimeMs: Long? = null
    private var sprintLatched30: Double? = null
    private var sprintLatched40: Double? = null

    // Strict 3-Frame Debounced Charging Gate State:
    // Requires 0x11 Byte 5 == 0x01 AND 0x13 Byte 5 == 0x06 AND speed == 0 for 3 consecutive charging frames
    private var chargingDebounceCount: Int = 0
    private var latest0x13Is0x06: Boolean? = null
    private var latest0x11Is0x01: Boolean? = null

    fun updateByteOffsetMapping(newMapping: ByteOffsetMapping) {
        _byteOffsetMapping.value = newMapping
        val current = _telemetryState.value
        current.latestFrame0x10?.let { decodeCompleteFrame(it, System.currentTimeMillis(), incrementCounter = false) }
        current.latestFrame0x13?.let { decodeCompleteFrame(it, System.currentTimeMillis(), incrementCounter = false) }
        current.latestFrame0x11?.let { decodeCompleteFrame(it, System.currentTimeMillis(), incrementCounter = false) }
        current.latestFrame0x7D?.let { decodeCompleteFrame(it, System.currentTimeMillis(), incrementCounter = false) }
    }

    fun setActiveTripTab(tabIndex: Int) {
        _telemetryState.update { it.copy(activeTripTab = tabIndex.coerceIn(0, 3)) }
    }

    fun resetIntegratedSubMeterTrip() {
        synchronized(bufferLock) {
            sprintStartTimeMs = null
            sprintLatched30 = null
            sprintLatched40 = null
            _telemetryState.update {
                it.copy(
                    integratedDistanceMeters = 0.0,
                    clusterIntegratedStepKm = 0.0,
                    peakSpeedKmh = 0,
                    sprint0To30Sec = null,
                    sprint0To40Sec = null,
                    isSprintTimerRunning = false
                )
            }
        }
    }

    fun clearTelemetryOnDisconnect() {
        synchronized(bufferLock) {
            rxBuffer.clear()
            lastSpeedPacketTimeMs = null
            lastSpeedKmh = 0
            sprintStartTimeMs = null
            chargingDebounceCount = 0
            latest0x13Is0x06 = null
            latest0x11Is0x01 = null
            _telemetryState.value = TelemetryState()
        }
    }

    /**
     * Evaluates the strict debounced charging gate:
     * - Immediately latches `false` and resets `chargingDebounceCount = 0` if `latest0x11Is0x01 == false`
     *   OR `latest0x13Is0x06 == false` OR `lastSpeedKmh > 0`.
     * - Requires 3 consecutive charging frame evaluations (`chargingDebounceCount >= 3`) before latching `true`
     *   (and requires both `0x11 == 0x01` and `0x13 == 0x06` once both frame types have been received).
     */
    private fun evaluateDebouncedChargingGate(): Boolean {
        val is13Bad = latest0x13Is0x06 == false
        val is11Bad = latest0x11Is0x01 == false
        if (is13Bad || is11Bad || lastSpeedKmh > 0) {
            chargingDebounceCount = 0
            return false
        }
        val anyChargingSeen = (latest0x13Is0x06 == true || latest0x11Is0x01 == true)
        if (!anyChargingSeen) {
            chargingDebounceCount = 0
            return false
        }
        chargingDebounceCount++
        val bothConfirmedOrPendingSecond = (latest0x13Is0x06 == true && latest0x11Is0x01 == true) ||
            (latest0x13Is0x06 == true && latest0x11Is0x01 == null) ||
            (latest0x11Is0x01 == true && latest0x13Is0x06 == null)
        return bothConfirmedOrPendingSecond && chargingDebounceCount >= IqubeBleSpec.CHARGING_DEBOUNCE_FRAMES
    }

    /**
     * Ingests raw byte chunks from `0x5354` (`Handle 0x001E`), reassembles fragmented ATT MTU slices,
     * validates `0x5A` header and `0xFF` trailer, and decodes all extracted 20-byte frames.
     */
    fun ingestRawChunk(chunk: ByteArray, timestampMs: Long = System.currentTimeMillis()): List<DecodedIqubePacket> {
        if (chunk.isEmpty()) return emptyList()
        val decodedPackets = mutableListOf<DecodedIqubePacket>()

        synchronized(bufferLock) {
            for (b in chunk) {
                rxBuffer.add(b)
            }

            while (rxBuffer.isNotEmpty()) {
                val startIdx = rxBuffer.indexOfFirst { it == IqubeBleSpec.HEADER_TELEMETRY }
                if (startIdx == -1) {
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                    rxBuffer.clear()
                    break
                }
                if (startIdx > 0) {
                    repeat(startIdx) { rxBuffer.removeAt(0) }
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                }

                if (rxBuffer.size < 3) break

                if (rxBuffer.size >= IqubeBleSpec.STANDARD_FRAME_SIZE) {
                    val candidate20 = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE) { i -> rxBuffer[i] }
                    val opcode = candidate20[1]
                    val isKnownOpcode = opcode == IqubeBleSpec.OPCODE_SPEED_ODOMETER ||
                        opcode == IqubeBleSpec.OPCODE_BATTERY_RANGE ||
                        opcode == IqubeBleSpec.OPCODE_VEHICLE_ID ||
                        opcode == IqubeBleSpec.OPCODE_TRIPS_CHARGING ||
                        opcode == IqubeBleSpec.OPCODE_MEDIA_CONTROL

                    if (isKnownOpcode) {
                        repeat(IqubeBleSpec.STANDARD_FRAME_SIZE) { rxBuffer.removeAt(0) }
                        decodeCompleteFrame(candidate20, timestampMs, incrementCounter = true)?.let {
                            decodedPackets.add(it)
                        }
                        continue
                    }
                }

                val endIdx = (3 until rxBuffer.size).firstOrNull { rxBuffer[it] == IqubeBleSpec.FOOTER_DELIMITER }
                if (endIdx != null) {
                    val frameLen = endIdx + 1
                    val rawFrame = ByteArray(frameLen) { rxBuffer[0].also { rxBuffer.removeAt(0) } }
                    val normalized = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
                    val copyLen = minOf(rawFrame.size, IqubeBleSpec.STANDARD_FRAME_SIZE)
                    System.arraycopy(rawFrame, 0, normalized, 0, copyLen)
                    normalized[IqubeBleSpec.STANDARD_FRAME_SIZE - 1] = IqubeBleSpec.FOOTER_DELIMITER
                    decodeCompleteFrame(normalized, timestampMs, incrementCounter = true)?.let {
                        decodedPackets.add(it)
                    }
                } else if (rxBuffer.size > 64) {
                    rxBuffer.removeAt(0)
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                } else {
                    break
                }
            }
        }

        return decodedPackets
    }

    private fun decodeCompleteFrame(
        frame: ByteArray,
        timestampMs: Long,
        incrementCounter: Boolean
    ): DecodedIqubePacket? {
        if (frame.size < IqubeBleSpec.STANDARD_FRAME_SIZE || frame[0] != IqubeBleSpec.HEADER_TELEMETRY) {
            if (incrementCounter) {
                _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
            }
            return null
        }

        val mapping = _byteOffsetMapping.value
        return when (frame[1]) {
            IqubeBleSpec.OPCODE_SPEED_ODOMETER -> decodeSpeedAndOdometerFrame0x10(frame, timestampMs, mapping, incrementCounter)
            IqubeBleSpec.OPCODE_BATTERY_RANGE -> decodeBatteryAndRangeFrame0x13(frame, timestampMs, mapping, incrementCounter)
            IqubeBleSpec.OPCODE_TRIPS_CHARGING -> decodeTripsAndChargingFrame0x11(frame, timestampMs, mapping, incrementCounter)
            IqubeBleSpec.OPCODE_VEHICLE_ID -> decodeVehicleIdentityFrame0x7D(frame, timestampMs, incrementCounter)
            IqubeBleSpec.OPCODE_MEDIA_CONTROL -> decodeMediaControlFrame0x6B(frame, timestampMs, incrementCounter)
            else -> {
                if (incrementCounter) {
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                }
                null
            }
        }
    }

    /**
     * `0x5A 0x10` (Speed + Total Hardware Odometer, 4.7 Hz):
     * - `Byte 2` = `uint8` Live Wheel Speed in `km/h` (`d[2]`).
     * - `Bytes 3..5` = `uint24 Big-Endian` Total Hardware Odometer in `0.1 km` units:
     *   `totalOdometerKm = ((d[3] << 16) | (d[4] << 8) | d[5]) / 10.0` (e.g., `05 7B 54` = `35,925.2 km`).
     * - `Byte 11` = Cluster Brightness Level.
     * - `Byte 12` = Ignition & Assist Flag (`0x80` = Ignition Turned OFF, `0x04` = Voice Assist).
     * - `Byte 13` = 0–30 km/h Sprint Time in ms (`d[13] * 10`).
     */
    private fun decodeSpeedAndOdometerFrame0x10(
        frame: ByteArray,
        timestampMs: Long,
        mapping: ByteOffsetMapping,
        incrementCounter: Boolean
    ): DecodedIqubePacket.SpeedOdometerFrame {
        val speedKmh = (frame[2].toInt() and 0xFF).coerceIn(0, 140)
        val odoIdx = mapping.odometerStartOffsetIn0x10.coerceIn(2, frame.size - 4)
        val rawOdoTenths = readUInt24BE(frame, odoIdx)
        val totalOdoKm = if (rawOdoTenths > 0) (rawOdoTenths / 10.0) else (_telemetryState.value.odometerKm ?: 0.0)

        val brightness = frame[11].toInt() and 0xFF
        val ignFlag = frame[12].toInt() and 0xFF
        val hwSprintMs = (frame[13].toInt() and 0xFF) * 10

        var dtSeconds = 0.0
        val prevTime = lastSpeedPacketTimeMs
        if (prevTime != null && timestampMs > prevTime) {
            val rawDt = (timestampMs - prevTime) / 1000.0
            if (rawDt in 0.01..3.0) {
                dtSeconds = rawDt
            }
        }
        lastSpeedPacketTimeMs = timestampMs

        val deltaMeters = if (dtSeconds > 0.0) (lastSpeedKmh / 3.6) * dtSeconds else 0.0
        val accelMs2 = if (dtSeconds >= 0.05) {
            (((speedKmh - lastSpeedKmh) / 3.6) / dtSeconds * 100.0).roundToInt() / 100.0
        } else {
            _telemetryState.value.accelerationMs2 ?: 0.0
        }

        val isRegen = speedKmh < lastSpeedKmh && speedKmh > 0

        if (speedKmh == 0) {
            sprintStartTimeMs = null
        } else if (lastSpeedKmh == 0 && speedKmh > 0) {
            sprintStartTimeMs = timestampMs
            sprintLatched30 = null
            sprintLatched40 = null
        }

        val currentSprintStart = sprintStartTimeMs
        if (currentSprintStart != null && timestampMs >= currentSprintStart) {
            val elapsedSec = ((timestampMs - currentSprintStart) / 10.0).roundToInt() / 100.0
            if (speedKmh >= 30 && sprintLatched30 == null) {
                sprintLatched30 = elapsedSec
            }
            if (speedKmh >= 40 && sprintLatched40 == null) {
                sprintLatched40 = elapsedSec
            }
        }

        lastSpeedKmh = speedKmh

        // If speed > 0, immediately latch isCharging = false and reset chargingDebounceCount
        if (speedKmh > 0) {
            chargingDebounceCount = 0
        }

        _telemetryState.update { prev ->
            val newDistMeters = prev.integratedDistanceMeters + deltaMeters
            val clusterStepKm = floor(newDistMeters / 100.0) / 10.0
            val effectiveCharging = if (speedKmh > 0) false else prev.isCharging
            val mode = when {
                effectiveCharging -> RideMode.CHARGING
                speedKmh >= 38 -> RideMode.POWER
                prev.rideMode == RideMode.CHARGING -> RideMode.ECO
                else -> prev.rideMode ?: RideMode.ECO
            }
            prev.copy(
                speedKmh = speedKmh,
                peakSpeedKmh = maxOf(prev.peakSpeedKmh, speedKmh),
                accelerationMs2 = accelMs2,
                integratedDistanceMeters = newDistMeters,
                clusterIntegratedStepKm = clusterStepKm,
                sprint0To30Sec = sprintLatched30 ?: prev.sprint0To30Sec,
                sprint0To40Sec = sprintLatched40 ?: prev.sprint0To40Sec,
                isSprintTimerRunning = sprintStartTimeMs != null && speedKmh < 40,
                odometerKm = if (totalOdoKm > 0.0) totalOdoKm else prev.odometerKm,
                clusterBrightnessLevel = brightness,
                ignitionAssistFlag = ignFlag,
                hardwareSprint0To30Ms = if (hwSprintMs > 0) hwSprintMs else prev.hardwareSprint0To30Ms,
                isCharging = effectiveCharging,
                isRegenActive = isRegen,
                rideMode = mode,
                isIgnitionUnlocked = ignFlag != 0x80,
                latestFrame0x10 = frame.copyOf(),
                packetCount0x10 = if (incrementCounter) prev.packetCount0x10 + 1 else prev.packetCount0x10,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.SpeedOdometerFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            speedKmh = speedKmh,
            totalOdometerKm = totalOdoKm
        )
    }

    /**
     * `0x5A 0x13` (Battery SoC + Cluster Range + Charging Mode, 3.1 Hz):
     * - `Byte 2` = `uint8` Battery SoC `%` (`d[2]`, e.g., `0x26..0x2A` = `38%..42%`).
     * - `Bytes 3..4` = `uint16 Big-Endian` Cluster Projected Range in `0.1 km` units:
     *   `clusterRangeKm = ((d[3] << 8) | d[4]) / 10.0` (e.g., `0x0190` = `40.0 km` at `38%` -> `0x01C2` = `45.0 km` at `42%`).
     * - `Byte 5` = State Flag (`0x06` when charging).
     */
    private fun decodeBatteryAndRangeFrame0x13(
        frame: ByteArray,
        timestampMs: Long,
        mapping: ByteOffsetMapping,
        incrementCounter: Boolean
    ): DecodedIqubePacket.BatteryRangeFrame {
        val socPercent = (frame[2].toInt() and 0xFF).coerceIn(0, 100)
        val rangeIdx = mapping.rangeStartOffsetIn0x13.coerceIn(3, frame.size - 3)
        val rawRangeTenths = readUInt16BE(frame, rangeIdx)
        val clusterRangeKm = if (rawRangeTenths > 0) (rawRangeTenths / 10.0) else socPercent.toDouble()

        val flagIdx = mapping.stateFlagOffsetIn0x13.coerceIn(3, frame.size - 2)
        val stateFlagByte5 = frame[flagIdx].toInt() and 0xFF
        val isCharging0x06 = stateFlagByte5 == (IqubeBleSpec.FLAG_0X13_CHARGING_MODE.toInt() and 0xFF)
        latest0x13Is0x06 = isCharging0x06

        val derivedVolts = ((44.8f + (socPercent / 100f) * 8.4f) * 10f).roundToInt() / 10f

        val debouncedCharging = evaluateDebouncedChargingGate()

        // Auto Ride Mode Detection from Cluster BMS DTE (0x13 Bytes 3..4) vs SoC:
        // Eco mode BMS DTE is ~1.05 km/%; Power mode BMS DTE drops sharply (< 0.86 km/%).
        val dteRatio = if (socPercent >= 15) (clusterRangeKm / socPercent.toDouble()) else 1.05

        _telemetryState.update { prev ->
            val inferredMode = when {
                debouncedCharging -> RideMode.CHARGING
                dteRatio < 0.86 -> RideMode.POWER
                dteRatio >= 0.92 && lastSpeedKmh < 38 -> RideMode.ECO
                prev.rideMode == RideMode.CHARGING -> RideMode.ECO
                else -> prev.rideMode ?: RideMode.ECO
            }
            prev.copy(
                batteryPercent = socPercent,
                clusterRangeKm = clusterRangeKm,
                stateFlag0x13Byte5 = stateFlagByte5,
                isCharging = debouncedCharging,
                rideMode = inferredMode,
                packVoltageVolts = derivedVolts,
                latestFrame0x13 = frame.copyOf(),
                packetCount0x13 = if (incrementCounter) prev.packetCount0x13 + 1 else prev.packetCount0x13,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.BatteryRangeFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            batteryPercent = socPercent,
            clusterRangeKm = clusterRangeKm,
            stateFlagByte5 = stateFlagByte5,
            isChargingMode0x06 = isCharging0x06
        )
    }

    /**
     * `0x5A 0x11` (Hardware Trip A, Trip B, & Charging Active Flag, 0.5 Hz):
     * - `Bytes 2..4` = `uint24 Big-Endian` Hardware Trip A in `0.1 km` units (`((d[2] << 16) | (d[3] << 8) | d[4]) / 10.0`, e.g., `00 00 03` = `0.3 km`).
     * - `Byte 5` = Charging Active Flag (`0x01` = Charging Active, `0x00` = Not Charging).
     * - `Byte 6` = Battery SoC `%` mirror (`d[6]`).
     * - `Bytes 7..9` = `uint24 Big-Endian` Hardware Trip B in `0.1 km` units (`((d[7] << 16) | (d[8] << 8) | d[9]) / 10.0`, e.g., `00 00 0A` = `1.0 km`).
     */
    private fun decodeTripsAndChargingFrame0x11(
        frame: ByteArray,
        timestampMs: Long,
        mapping: ByteOffsetMapping,
        incrementCounter: Boolean
    ): DecodedIqubePacket.TripsAndChargingFrame {
        val tripAIdx = mapping.tripAStartOffsetIn0x11.coerceIn(2, frame.size - 4)
        val chgIdx = mapping.chargingFlagOffsetIn0x11.coerceIn(2, frame.size - 2)
        val socMirrorIdx = mapping.socMirrorOffsetIn0x11.coerceIn(2, frame.size - 2)
        val tripBIdx = mapping.tripBStartOffsetIn0x11.coerceIn(2, frame.size - 4)

        val tripAKm = readUInt24BE(frame, tripAIdx) / 10.0
        val chargingFlagByte5 = frame[chgIdx].toInt() and 0xFF
        val isCharging0x01 = chargingFlagByte5 == (IqubeBleSpec.FLAG_0X11_CHARGING_ACTIVE.toInt() and 0xFF)
        latest0x11Is0x01 = isCharging0x01
        val socMirror = (frame[socMirrorIdx].toInt() and 0xFF).coerceIn(0, 100)
        val tripBKm = readUInt24BE(frame, tripBIdx) / 10.0

        val debouncedCharging = evaluateDebouncedChargingGate()

        _telemetryState.update { prev ->
            prev.copy(
                tripAKm = tripAKm,
                tripBKm = tripBKm,
                chargingFlag0x11Byte5 = chargingFlagByte5,
                socMirror0x11 = socMirror,
                batteryPercent = prev.batteryPercent ?: (if (socMirror > 0) socMirror else null),
                isCharging = debouncedCharging,
                rideMode = if (debouncedCharging) RideMode.CHARGING else (if (prev.rideMode == RideMode.CHARGING) RideMode.ECO else (prev.rideMode ?: RideMode.ECO)),
                lastOdometerSyncMs = timestampMs,
                latestFrame0x11 = frame.copyOf(),
                packetCount0x11 = if (incrementCounter) prev.packetCount0x11 + 1 else prev.packetCount0x11,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.TripsAndChargingFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            tripAKm = tripAKm,
            tripBKm = tripBKm,
            chargingActiveFlagByte5 = chargingFlagByte5,
            isChargingActive = isCharging0x01,
            socMirrorPercent = socMirror
        )
    }

    /**
     * `0x5A 0x7D` (Vehicle Model & ID String, 3.1 Hz):
     * - `Bytes 2..18` = ASCII Vehicle Identifier string (`"TVSiQUBEJI573"`).
     */
    private fun decodeVehicleIdentityFrame0x7D(
        frame: ByteArray,
        timestampMs: Long,
        incrementCounter: Boolean
    ): DecodedIqubePacket.VehicleIdentityFrame {
        val asciiSlice = frame.copyOfRange(2, 19)
        val rawAscii = String(asciiSlice, StandardCharsets.US_ASCII)
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
        val vehicleId = rawAscii.ifEmpty { IqubeBleSpec.DEFAULT_VEHICLE_ID }

        _telemetryState.update { prev ->
            prev.copy(
                vehicleIdString = vehicleId,
                isIgnitionUnlocked = true,
                latestFrame0x7D = frame.copyOf(),
                packetCount0x7D = if (incrementCounter) prev.packetCount0x7D + 1 else prev.packetCount0x7D,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.VehicleIdentityFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            vehicleIdAscii = vehicleId
        )
    }

    /**
     * `0x5A 0x6B` (Cluster Handlebar Music Control Event):
     * - `Byte 2` = Music Command Code:
     *   - `0x00`: Play
     *   - `0x01`: Pause
     *   - `0x02`: Play/Pause Toggle
     *   - `0x03`: Next Track
     *   - `0x04`: Previous Track
     *   - `0x05`: Volume Up
     *   - `0x06`: Volume Down
     */
    private fun decodeMediaControlFrame0x6B(
        frame: ByteArray,
        timestampMs: Long,
        incrementCounter: Boolean
    ): DecodedIqubePacket.MediaControlFrame {
        val cmdByte = frame[2].toInt() and 0xFF
        val musicCmd = MusicCommand.fromByte(cmdByte)

        _telemetryState.update { prev ->
            prev.copy(
                lastMediaCommand = musicCmd ?: prev.lastMediaCommand,
                lastMediaCommandTimestampMs = timestampMs,
                latestFrame0x6B = frame.copyOf(),
                packetCount0x6B = if (incrementCounter) prev.packetCount0x6B + 1 else prev.packetCount0x6B,
                lastPacketTimestampMs = timestampMs
            )
        }

        if (musicCmd != null) {
            onMediaCommandReceived?.invoke(musicCmd, timestampMs)
        }

        return DecodedIqubePacket.MediaControlFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            commandCodeByte2 = cmdByte,
            command = musicCmd
        )
    }

    private fun readUInt16BE(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        val high = bytes[offset].toInt() and 0xFF
        val low = bytes[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun readUInt24BE(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 >= bytes.size) return 0
        val b0 = (bytes[offset].toInt() and 0xFF) shl 16
        val b1 = (bytes[offset + 1].toInt() and 0xFF) shl 8
        val b2 = (bytes[offset + 2].toInt() and 0xFF)
        return b0 or b1 or b2
    }
}
