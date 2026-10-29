package com.example.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Target BLE Hardware & UUID Constants for TVS iQube (SmartXonnect BLE Cluster)
 * Verified from unfiltered btsnoop_hci.log (#229, #270, #315), btsnooz_hci.log, and decompiled SmartXonnect smali.
 */
object IqubeBleSpec {
    const val TARGET_MAC_ADDRESS = "F8:55:48:72:96:DA"
    const val ADVERTISED_BLE_NAME = "TVSiQUBECJI573"
    const val DEFAULT_VEHICLE_ID = "TVSiQUBEJI573"
    const val CLUSTER_VEHICLE_ID_DEFAULT = "TVSiQUBEJI573"
    const val NAME_FILTER_PREFIX = "TVS"

    // Custom 128-bit SmartXonnect Primary Service UUID (ASCII: "TVSMVGSASBENTORQ")
    const val SERVICE_UUID_SMARTXONNECT = "5456534d-5647-5341-5342-454e544f5251"
    const val SERVICE_UUID_ASCII = "TVSMVGSASBENTORQ"
    // Telemetry Notify Characteristic (Handle 0x001E, 16-bit 0x5354, ASCII "ST")
    const val CHAR_UUID_TELEMETRY_NOTIFY = "00005354-0000-1000-8000-00805f9b34fb"
    // Command Write Characteristic (Handle 0x0022, 16-bit 0x5352, ASCII "SR")
    const val CHAR_UUID_COMMAND_WRITE = "00005352-0000-1000-8000-00805f9b34fb"
    const val DESCRIPTOR_UUID_CCCD = "00002902-0000-1000-8000-00805f9b34fb"       // Handle 0x001F
    const val DESCRIPTOR_UUID_CUD = "00002901-0000-1000-8000-00805f9b34fb"        // Handle 0x0023

    // Protocol Framing Bytes (Every 20-byte frame sent or received ends with Byte 19 = 0xFF)
    const val HEADER_TELEMETRY: Byte = 0x5A // 'Z' (90 decimal)
    const val HEADER_ASCII_CMD: Byte = 0x5B // '[' (91 decimal)
    const val FOOTER_DELIMITER: Byte = 0xFF.toByte()
    const val STANDARD_FRAME_SIZE = 20
    const val MAX_LOCATION_ASCII_BYTES = 17 // Bytes 2..18 (17 UTF-8/ASCII chars), Byte 19 = 0xFF
    const val MAX_NAV_STREET_ASCII_BYTES = 15 // Bytes 4..18 (15 UTF-8/ASCII chars), Byte 19 = 0xFF

    // Incoming Telemetry Opcodes (Scooter -> Phone on 0x5354 / Handle 0x001E)
    const val OPCODE_SPEED_ODOMETER: Byte = 0x10 // Byte 2 = speed km/h, Bytes 3..5 = uint24 BE total odometer (0.1 km)
    const val OPCODE_TRIPS_CHARGING: Byte = 0x11 // Bytes 2..4 = Trip A (0.1 km), Byte 5 = Charging (0x01), Byte 6 = SoC mirror, Bytes 7..9 = Trip B (0.1 km)
    const val OPCODE_BATTERY_RANGE: Byte = 0x13  // Byte 2 = SoC %, Bytes 3..4 = uint16 BE Cluster Range (0.1 km), Byte 5 = State Flag (0x06 charging)
    const val OPCODE_MEDIA_CONTROL: Byte = 0x6B  // Byte 2 = Handlebar Media Key (0x00=Play, 0x01=Pause, 0x02=Toggle, 0x03=Next, 0x04=Prev, 0x05=Vol+, 0x06=Vol-)
    const val OPCODE_VEHICLE_ID: Byte = 0x7D     // Bytes 2..18 = ASCII Vehicle ID ("TVSiQUBEJI573")

    // Outgoing Command Data IDs on 0x5352 (Handle 0x0022)
    const val DATA_ID_CALLER_43: Byte = 0x43       // 'C' — createMobileToClusterArray (0x5B 0x43)
    const val DATA_ID_NAV_MANEUVER_49: Byte = 0x49 // 'I' — Navigation Maneuver & Distance (0x5B 0x49)
    const val DATA_ID_HEARTBEAT_4A: Byte = 0x4A    // 'J' — sendIQubeMobileData (0x5B 0x4A)
    const val DATA_ID_NAV_ASSIST_4E: Byte = 0x4E   // 'N' — byteArrayForNavigation control frame (0x5A 0x4E / 0x5B 0x4E)
    const val DATA_ID_NAV_INSTRUCTION_4F: Byte = 0x4F // 'O' — byteArrayForNavigation instruction frame (0x5B 0x4F)
    const val DATA_ID_HANDSHAKE_52: Byte = 0x52    // 'R' — Handshake [RU (0x5B 0x52 0x55) & Rider Greeting (0x5B 0x52)
    const val DATA_ID_LOCATION_5C: Byte = 0x5C     // '\' — GPS Locality Text (0x5B 0x5C)
    const val DATA_ID_CALL_STATE_5D: Byte = 0x5D   // ']' — writeCallStateForIQube (0x5B 0x5D 0x43)
    const val SONG_NAME_PACKET: Byte = 0x62        // 'b' — Music Track Title Banner (0x5B 0x62)

    // Verified Trip Subcommands for createVehicleDataArray (0x5A 0xF1 0x00 <tripVal> ... 0xFF):
    const val SUBCMD_SWITCH_TRIP_A: Byte = 0x00
    const val SUBCMD_SWITCH_TRIP_B: Byte = 0x01
    const val SUBCMD_RESET_ACTIVE_TRIP: Byte = 0x02

    // Call State Bytes for writeCallStateForIQube (0x5B 0x5D 0x43 <state>)
    const val CALL_STATE_RINGING: Byte = 0x00
    const val CALL_STATE_ANSWERED: Byte = 0x01
    const val CALL_STATE_ENDED: Byte = 0x02
    const val CALL_STATE_MISSED: Byte = 0x03
    const val CALL_STATE_OUTGOING: Byte = 0x04

    fun computePhoneBatteryBars(batteryPct: Int): Int {
        val pct = batteryPct.coerceIn(0, 100)
        return when {
            pct <= 10 -> 0
            pct <= 30 -> 1
            pct <= 55 -> 2
            pct <= 80 -> 3
            else -> 4
        }
    }

    // Navigation Unit Bytes for 0x5B 0x49 (Byte 5)
    const val NAV_UNIT_METERS: Byte = 0x01
    const val NAV_UNIT_KILOMETERS: Byte = 0x02

    // Charging State Constants
    const val FLAG_0X13_CHARGING_MODE: Byte = 0x06
    const val FLAG_0X11_CHARGING_ACTIVE: Byte = 0x01
    const val CHARGING_DEBOUNCE_FRAMES = 3           // 3-frame hysteresis debounce before latching charging = true
    const val VERIFIED_CAPTURE_SEC_PER_PCT = 115.7   // +31.1% / hr from btsnoop_hci.log charging capture
    const val DEFAULT_0_TO_80_SEC_PER_PCT = 123.75   // 2 hr 45 min (9,900s) for 0% -> 80%
    const val DEFAULT_80_TO_100_SEC_PER_PCT = 180.0  // 3 min (180s) per 1% taper for 80% -> 100%
    const val DEFAULT_CHARGING_SEC_PER_PCT = 123.75

    // Link, FIFO Spacing & Timing Constants
    const val REQUESTED_MTU = 517
    const val EXPECTED_NEGOTIATED_MTU = 247
    const val INTER_COMMAND_SPACING_MS = 120L        // 120 ms minimum spacing between FIFO GATT writes
    const val NAVIGATION_INTER_PACKET_DELAY_MS = 400L // 400 ms (0x190 ms) sleep between Nav Packet 1 (0x5B 0x49) and Packet 2 (0x5B 0x4E)
    const val CALLER_REPEAT_TIMER_MS = 500L          // 500 ms iQubeCallerDataSendTimer while ringing
    const val WRITE_ACK_TIMEOUT_MS = 500L
    const val LINK_WATCHDOG_DEGRADED_MS = 2500L
    const val SUPERVISION_TIMEOUT_MS = 6000L
    const val HEARTBEAT_INTERVAL_MS = 1800L
    const val LOCATION_SYNC_INTERVAL_MS = 10_000L
    const val PHONE_STATUS_SYNC_INTERVAL_MS = 60_000L

    // True Range Baseline Constants
    const val DEFAULT_ECO_KM_PER_PCT = 1.00   // 100 km at 100% SoC
    const val DEFAULT_POWER_KM_PER_PCT = 0.75 // 75 km at 100% SoC
    const val POWER_TO_ECO_RATIO = 0.75
}

/**
 * Handlebar Media Key Command Opcodes emitted by the cluster in frame `0x5A 0x6B` (`Byte [2]`).
 */
enum class MusicCommand(val code: Int, val label: String) {
    PLAY(0x00, "Play"),
    PAUSE(0x01, "Pause"),
    PLAY_PAUSE_TOGGLE(0x02, "Play/Pause Toggle"),
    NEXT_TRACK(0x03, "Next Track"),
    PREVIOUS_TRACK(0x04, "Previous Track"),
    VOLUME_UP(0x05, "Volume Up"),
    VOLUME_DOWN(0x06, "Volume Down");

    companion object {
        fun fromByte(b: Int): MusicCommand? = entries.firstOrNull { it.code == (b and 0xFF) }
    }
}

/**
 * Complete Turn-by-Turn Navigation Maneuver & Pictogram Constants extracted from
 * Mappls `TurnTypeConstants.smali` (classes7.dex), `BleNavigationDataSourceImp` (classes12.dex),
 * and `BleNavigationSendData$Companion` (classes12.dex).
 */
enum class TurnTypeConstant(val code: Int, val title: String, val symbol: String) {
    // --- Original 22 Verified Core Maneuvers ---
    TURN_LEFT(0x00, "Turn Left", "↰"),
    TURN_SHARPLY_LEFT(0x01, "Turn Sharply Left", "↙"),
    TURN_SLIGHTLY_LEFT(0x02, "Turn Slightly Left", "↖"),
    TURN_RIGHT(0x03, "Turn Right", "↱"),
    TURN_SHARPLY_RIGHT(0x04, "Turn Sharply Right", "↘"),
    TURN_SLIGHTLY_RIGHT(0x05, "Turn Slightly Right", "↗"),
    U_TURN(0x06, "U-Turn (Left)", "↶"),
    CONTINUE_STRAIGHT(0x07, "Continue Straight", "↑"),
    DESTINATION_REACHED(0x08, "Destination Reached", "🏁"),
    KEEP_LEFT(0x09, "Keep Left", "⇖"),
    KEEP_RIGHT(0x0A, "Keep Right", "⇗"),
    TAKE_LEFT_AT(0x0B, "Take Left At", "↰"),
    TAKE_RIGHT_AT(0x0C, "Take Right At / Off Route", "↱"),
    TAKE_LEFT_AT_END(0x0D, "Take Left At End", "↰"),
    TAKE_RIGHT_AT_END(0x0E, "Take Right At End", "↱"),
    FORK_LEFT(0x0F, "Fork Left", "⑂←"),
    FORK_RIGHT(0x10, "Fork Right", "⑂→"),
    CONTINUE_STRAIGHT_AT_INTERSECTION(0x15, "Straight At Intersection", "┼↑"),
    RIGHT_U_TURN(0x29, "Right U-Turn", "↷"),
    ROUNDABOUT(0x44, "Roundabout (Exit 4)", "⟳"),
    ROUNDABOUT_LEFT(0x45, "Roundabout Left (Exit 5)", "⟲"),
    ROTARY(0x48, "Rotary / Traffic Circle", "❂"),

    // --- Additional 7 Mappls TurnTypeConstants (classes7.dex) ---
    KEEP_LEFT_AT_INTERSECTION(0x13, "Keep Left At Intersection", "┼⇖"),
    KEEP_RIGHT_AT_INTERSECTION(0x14, "Keep Right At Intersection", "┼⇗"),
    HIGHWAY_EXIT_LEFT(0x22, "Highway Exit Left", "🛣←"),
    HIGHWAY_ENTER_LEFT(0x23, "Highway Enter Left", "←🛣"),
    TAKE_FERRY(0x24, "Take Ferry / Highway Right", "⛴"),
    LEAVE_FERRY(0x25, "Leave Ferry", "⛴→"),
    BORDER_CROSSING(0x28, "Border Crossing", "🛂"),

    // --- Additional Roundabout Exits & MMIv2 Pictograms (0x30, 0x41..0x4B in classes12.dex) ---
    ROUNDABOUT_PASS(0x30, "Roundabout Pass", "⟳↑"),
    ROUNDABOUT_EXIT_1(0x41, "Roundabout Exit 1", "⟳①"),
    ROUNDABOUT_EXIT_2(0x42, "Roundabout Exit 2", "⟳②"),
    ROUNDABOUT_EXIT_3(0x43, "Roundabout Exit 3 / Enter", "⟳③"),
    ROUNDABOUT_EXIT_6_RIGHT_2(0x46, "Roundabout Exit 6 / Right 2", "⟳⑥"),
    ROUNDABOUT_EXIT_7_RIGHT_1(0x47, "Roundabout Exit 7 / Right 1", "⟳⑦"),
    ROUNDABOUT_STRAIGHT_MMI(0x49, "Roundabout Straight (MMIv2)", "⟳↑"),
    ROUNDABOUT_LEFT_MMI(0x4A, "Roundabout Left (MMIv2)", "⟲←"),
    ROUNDABOUT_RIGHT_MMI(0x4B, "Roundabout Right (MMIv2)", "⟳→"),

    // --- Extended V1 / HERE / U546 / U725 Cluster Pictograms (classes12.dex & classes15.dex) ---
    RIGHT_FORK_V1(0x11, "Right Fork (V1)", "⑂→"),
    RIGHT_EXIT_RAMP_V1(0x12, "Right Exit / Ramp (V1)", "↱🛣"),
    ARRIVE_V1(0x16, "Arrive / Destination (V1)", "🏁"),
    FERRY_V1(0x17, "Ferry (V1)", "⛴"),
    LEFT_ROUNDABOUT_EXIT_1_V1(0x1B, "Left Roundabout Exit 1 (V1)", "⟲①"),
    LEFT_ROUNDABOUT_EXIT_2_V1(0x1C, "Left Roundabout Exit 2 (V1)", "⟲②"),
    LEFT_ROUNDABOUT_EXIT_3_V1(0x1D, "Left Roundabout Exit 3 (V1)", "⟲③"),
    RIGHT_ROUNDABOUT_ENTER_V1(0x1E, "Right Roundabout Enter (V1)", "⟳"),
    LEFT_ROUNDABOUT_EXIT_5_V1(0x1F, "Left Roundabout Exit 5 (V1)", "⟲⑤"),
    LEFT_ROUNDABOUT_EXIT_6_V1(0x20, "Left Roundabout Exit 6 (V1)", "⟲⑥"),
    LEFT_ROUNDABOUT_EXIT_7_V1(0x21, "Left Roundabout Exit 7 (V1)", "⟲⑦"),
    RIGHT_ROUNDABOUT_EXIT_5_V1(0x26, "Right Roundabout Exit 5 (V1)", "⟳⑤"),
    RIGHT_ROUNDABOUT_EXIT_6_V1(0x27, "Right Roundabout Exit 6 (V1)", "⟳⑥"),
    LEFT_ROUNDABOUT_EXIT_8_V1(0x2A, "Left Roundabout Exit 8 (V1)", "⟲⑧"),
    LEFT_ROUNDABOUT_EXIT_9_V1(0x2B, "Left Roundabout Exit 9 (V1)", "⟲⑨"),
    LEFT_ROUNDABOUT_EXIT_10_V1(0x2C, "Left Roundabout Exit 10 (V1)", "⟲⑩"),
    LEFT_ROUNDABOUT_EXIT_11_V1(0x2D, "Left Roundabout Exit 11 (V1)", "⟲⑪"),
    LEFT_ROUNDABOUT_EXIT_12_V1(0x2E, "Left Roundabout Exit 12 (V1)", "⟲⑫"),
    LEFT_ROUNDABOUT_ENTER_V1(0x2F, "Left Roundabout Enter (V1)", "⟲"),
    MIDDLE_FORK(0x31, "Middle Fork", "⑂↑"),
    RIGHT_ROUNDABOUT_EXIT_8_V1(0x32, "Right Roundabout Exit 8 (V1)", "⟳⑧"),
    RIGHT_ROUNDABOUT_EXIT_9_V1(0x33, "Right Roundabout Exit 9 (V1)", "⟳⑨"),
    RIGHT_ROUNDABOUT_EXIT_10_V1(0x34, "Right Roundabout Exit 10 (V1)", "⟳⑩"),
    RIGHT_ROUNDABOUT_EXIT_11_V1(0x35, "Right Roundabout Exit 11 (V1)", "⟳⑪"),
    RIGHT_ROUNDABOUT_EXIT_12_V1(0x36, "Right Roundabout Exit 12 (V1)", "⟳⑫"),
    RIGHT_ROUNDABOUT_ENTER_ALT(0x37, "Right Roundabout Enter Alt", "⟳"),
    RIGHT_ROUNDABOUT_PASS_V1(0x38, "Right Roundabout Pass (V1)", "⟳↑"),
    CONTINUE_ON_V1(0x39, "Continue Straight (V1)", "↑"),
    ROUNDABOUT_U_TURN_CW(0x3C, "Roundabout U-Turn CW", "⟳↶"),
    ROUNDABOUT_U_TURN_CCW(0x3D, "Roundabout U-Turn CCW", "⟲↷"),
    ROUNDABOUT_EXIT_CW(0x3E, "Roundabout Exit CW", "⟳↗"),
    ROUNDABOUT_EXIT_CCW(0x3F, "Roundabout Exit CCW", "⟲↖"),
    ROTARY_U725(0x40, "Rotary (U725)", "❂");

    val hexLabel: String
        get() = String.format(Locale.US, "0x%02X", code)
}

/**
 * State of the Turn-by-Turn Navigation 2-Packet Injector (`0x5B 0x49` + 400ms + `0x5B 0x4E`).
 */
data class NavigationManeuverState(
    val selectedTurn: TurnTypeConstant = TurnTypeConstant.TURN_LEFT,
    val distanceMeters: Int = 250,
    val unitByte: Int = 0x01, // 0x01 = meters (m), 0x02 = kilometers (0.1 km units)
    val etaMinutes: Int = 12,
    val streetName: String = "Wardha Rd",
    val isRouteSimulationRunning: Boolean = false,
    val currentRouteStepIndex: Int = 0,
    val totalRouteSteps: Int = 5,
    val lastPacket1Hex: String? = null,
    val lastPacket2Hex: String? = null,
    val lastSentTimestampMs: Long? = null
)

/**
 * Normalized telemetry extracted from an ongoing Google Maps (`com.google.android.apps.maps`)
 * Turn-by-Turn navigation notification.
 */
data class ParsedGoogleMapsNavigation(
    val direction: TurnTypeConstant,
    val distanceMeters: Int,
    val etaHours: Int,
    val etaMinutes: Int,
    val isDestinationArrived: Boolean = direction == TurnTypeConstant.DESTINATION_REACHED,
    val rawTitle: String = "",
    val rawText: String = "",
    val rawSubText: String = "",
    val streetOrLocationName: String = "",
    val turnCommand: com.example.ble.TurnCommand? = null
) {
    val totalEtaMinutes: Int
        get() = (etaHours.coerceAtLeast(0) * 60) + etaMinutes.coerceIn(0, 59)
}

/**
 * Live state of the crash-proof `GoogleMapsNotificationListenerService`.
 */
data class GoogleMapsListenerState(
    val isServiceConnected: Boolean = false,
    val isAutoForwardEnabled: Boolean = true,
    val isActiveNavigationOngoing: Boolean = false,
    val lastParsedNavigation: ParsedGoogleMapsNavigation? = null,
    val lastTurnCommand: com.example.ble.TurnCommand? = null,
    val dispatchedUpdateCount: Int = 0,
    val ignoredDuplicateCount: Int = 0,
    val throttledCountdownCount: Int = 0,
    val filteredNonNavCount: Int = 0,
    val lastStatusMessage: String = "Waiting for Google Maps ongoing navigation...",
    val lastUpdatedAtMs: Long? = null
)

/**
 * State of the Media / Music Track Banner (`0x5B 0x62`) and Handlebar Media Key (`0x5A 0x6B`) controller.
 */
data class MediaPlayerState(
    val trackTitle: String = "Midnight City",
    val artistName: String = "M83",
    val isPlaying: Boolean = false,
    val volumePercent: Int = 75,
    val playlistIndex: Int = 0,
    val lastHandlebarCommand: MusicCommand? = null,
    val lastHandlebarCommandTimestampMs: Long? = null,
    val lastSentBannerHex: String? = null
)

/**
 * State of the Smartphone Battery & Network Status Bar Sync (`0x5B 0x52 0x55`) and Clock Sync (`0x5B 0x4A 0x44`).
 */
data class PhoneStatusState(
    val phoneBatteryPct: Int = 85,
    val isBatteryOverrideEnabled: Boolean = false,
    val isPhoneCharging: Boolean = false,
    val signalBars: Int = 4,
    val missedCallsCount: Int = 0,
    val lastPhoneStatusSyncMs: Long? = null,
    val lastClockSyncMs: Long? = null
) {
    val phoneBatteryBars: Int
        get() = IqubeBleSpec.computePhoneBatteryBars(phoneBatteryPct)
}

enum class BleConnectionPhase {
    DISCONNECTED,
    SCANNING,
    CONNECTING_GATT,
    DISCOVERING_SERVICES,
    ENABLING_CCCD,
    NEGOTIATING_MTU,
    SENDING_HANDSHAKE,
    STREAMING_ACTIVE,
    REPLAYING_RIDING_LOG,
    REPLAYING_CHARGING_LOG,
    RECONNECTING_BACKOFF
}

enum class RideMode(val label: String) {
    ECO("ECO MODE"),
    POWER("POWER MODE"),
    CHARGING("CHARGING ACTIVE"),
    PARK("PARK / STANDBY")
}

/**
 * User-configurable Byte-Offset Mapper for incoming 20-byte frames.
 */
data class ByteOffsetMapping(
    val odometerStartOffsetIn0x10: Int = 3, // In 0x10 frame: Bytes 3..5 uint24 BE in 0.1 km (05 7B 54 = 35,925.2 km)
    val rangeStartOffsetIn0x13: Int = 3,    // In 0x13 frame: Bytes 3..4 uint16 BE in 0.1 km (03 F2 = 101.0 km)
    val stateFlagOffsetIn0x13: Int = 5,     // In 0x13 frame: Byte 5 = 0x06 when charging
    val tripAStartOffsetIn0x11: Int = 2,    // In 0x11 frame: Bytes 2..4 uint24 BE in 0.1 km (00 00 03 = 0.3 km)
    val chargingFlagOffsetIn0x11: Int = 5,  // In 0x11 frame: Byte 5 = 0x01 when charging
    val socMirrorOffsetIn0x11: Int = 6,     // In 0x11 frame: Byte 6 = SoC % mirror
    val tripBStartOffsetIn0x11: Int = 7     // In 0x11 frame: Bytes 7..9 uint24 BE in 0.1 km (00 00 0A = 1.0 km)
)

/**
 * Editable subcommand configuration for the 3 verified Trip Control buttons (`createVehicleDataArray` / `buildTripPacket`).
 * Defaults to `0x00` = Switch to Trip A, `0x01` = Switch to Trip B, `0x02` = Reset Active Trip.
 */
data class TripSubcommandConfig(
    val switchTripAByte: Int = IqubeBleSpec.SUBCMD_SWITCH_TRIP_A.toInt() and 0xFF, // 0x00
    val switchTripBByte: Int = IqubeBleSpec.SUBCMD_SWITCH_TRIP_B.toInt() and 0xFF, // 0x01
    val resetActiveTripByte: Int = IqubeBleSpec.SUBCMD_RESET_ACTIVE_TRIP.toInt() and 0xFF // 0x02
)

/**
 * Strongly-typed representation of a single decoded BLE packet from 0x5354 (Handle 0x001E).
 */
sealed class DecodedIqubePacket(
    open val timestampMs: Long,
    open val rawBytes: ByteArray
) {
    data class SpeedOdometerFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val speedKmh: Int,
        val totalOdometerKm: Double
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class BatteryRangeFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val batteryPercent: Int,
        val clusterRangeKm: Double,
        val stateFlagByte5: Int,
        val isChargingMode0x06: Boolean
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class TripsAndChargingFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val tripAKm: Double,
        val tripBKm: Double,
        val chargingActiveFlagByte5: Int,
        val isChargingActive: Boolean,
        val socMirrorPercent: Int
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class VehicleIdentityFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val vehicleIdAscii: String
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class MediaControlFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val commandCodeByte2: Int,
        val command: MusicCommand?
    ) : DecodedIqubePacket(timestampMs, rawBytes)
}

/**
 * Complete reactive Telemetry State exposed to the Compose UI.
 */
data class TelemetryState(
    // Speed & Total Hardware Odometer (from 0x5A 0x10)
    val speedKmh: Int? = null,
    val peakSpeedKmh: Int = 0,
    val accelerationMs2: Double? = null,
    val integratedDistanceMeters: Double = 0.0,
    val clusterIntegratedStepKm: Double = 0.0,
    val sprint0To30Sec: Double? = null,
    val sprint0To40Sec: Double? = null,
    val isSprintTimerRunning: Boolean = false,
    val odometerKm: Double? = null,
    val clusterBrightnessLevel: Int? = null,
    val ignitionAssistFlag: Int? = null,
    val hardwareSprint0To30Ms: Int? = null,

    // Battery SoC, Cluster BMS DTE Range & Charging Flag (from 0x5A 0x13 & 0x5A 0x11)
    val batteryPercent: Int? = null,
    val socMirror0x11: Int? = null,
    val clusterRangeKm: Double? = null,
    val stateFlag0x13Byte5: Int? = null,
    val chargingFlag0x11Byte5: Int? = null,
    val isCharging: Boolean = false,
    val packVoltageVolts: Float? = null,

    // Hardware Trip A & Trip B (from 0x5A 0x11)
    val tripAKm: Double? = null,
    val tripBKm: Double? = null,
    val activeTripTab: Int = 0, // 0 = Trip A, 1 = Trip B, 2 = ODO, 3 = Sub-Meter
    val lastOdometerSyncMs: Long? = null,

    // Vehicle Model & ID String (from 0x5A 0x7D Bytes 2..18)
    val vehicleIdString: String? = null,
    val rideMode: RideMode? = null,
    val isRegenActive: Boolean = false,
    val isIgnitionUnlocked: Boolean? = null,

    // Handlebar Media Control (from 0x5A 0x6B Byte 2)
    val lastMediaCommand: MusicCommand? = null,
    val lastMediaCommandTimestampMs: Long? = null,

    // Latest raw 20-byte frames per opcode for Live Hex Inspector
    val latestFrame0x10: ByteArray? = null,
    val latestFrame0x13: ByteArray? = null,
    val latestFrame0x7D: ByteArray? = null,
    val latestFrame0x11: ByteArray? = null,
    val latestFrame0x6B: ByteArray? = null,
    val packetCount0x10: Int = 0,
    val packetCount0x13: Int = 0,
    val packetCount0x7D: Int = 0,
    val packetCount0x11: Int = 0,
    val packetCount0x6B: Int = 0,
    val malformedPacketCount: Int = 0,
    val lastPacketTimestampMs: Long? = null
)

/**
 * Live Charging Monitor & 3-Tier Charging Predictor State.
 * - Tier 1 Default 0-80% baseline: `123.75s per +1%` (`2h 45m` for `0-80%`)
 * - Tier 2 Historical sessions average (`savedChargingSessions`)
 * - Tier 3 Live `+1%` step timing (`115.7s per +1%` = `+31.1%/hr`)
 * - Offline Predicted Continuation: Continues predicting SoC progression & firing target alarms
 *   if BLE disconnects while charging, flagging `needsCalibration = true` until reconnected/calibrated.
 */
data class ChargingMonitorState(
    val isCharging: Boolean = false,
    val isDisconnectedPredicted: Boolean = false,
    val needsCalibration: Boolean = false,
    val calibrationStatusLabel: String = "Calibrated (Live BMS)",
    val nextPercentProgressFraction: Float = 0f,
    val currentBatteryPct: Int? = null,
    val clusterRangeKm: Double? = null,
    val sessionStartBatteryPct: Int? = null,
    val disconnectStartBatteryPct: Int? = null,
    val pctGainedThisSession: Int = 0,
    val activeSecondsPerPct: Double = IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT,
    val baselineSecondsPerPct: Double = IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT,
    val liveMeasuredSecondsPerPct: Double? = null,
    val learned0To80SecPerPct: Double = IqubeBleSpec.DEFAULT_0_TO_80_SEC_PER_PCT,
    val learned80To100SecPerPct: Double = IqubeBleSpec.DEFAULT_80_TO_100_SEC_PER_PCT,
    val true0To80TotalSeconds: Long = 9900L,
    val true0To100TotalSeconds: Long = 13500L,
    val chargingSpeedPctPerHour: Double = 29.1,
    val remainingSecondsTo80: Long? = null,
    val remainingSecondsTo100: Long? = null,
    val baselineRemainingSecondsTo80: Long? = null,
    val baselineRemainingSecondsTo100: Long? = null,
    val predictionModeLabel: String = "Tier 1: Default Baseline (2h 45m 0–80% = 123.75s/%)",
    val isUsingLiveStepTiming: Boolean = false,
    val completedStepsCount: Int = 0,
    val contributingSessionsCount: Int = 0,
    val totalPctLearned: Int = 0,
    val estimatedKwhAddedThisSession: Double = 0.0,
    val chargingInsightText: String = "Default charging curve active (0–80% @ 123.8s/% • 80–100% @ 180.0s/%)"
)

data class SavedChargingSession(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startPct: Int,
    val endPct: Int,
    val pctGained: Int,
    val durationSeconds: Long,
    val avgSecondsPerPct: Double,
    val cc0To80PctGained: Int = pctGained,
    val cc0To80SecondsPerPct: Double = avgSecondsPerPct,
    val cv80To100PctGained: Int = 0,
    val cv80To100SecondsPerPct: Double = IqubeBleSpec.DEFAULT_80_TO_100_SEC_PER_PCT,
    val estimatedKwhAdded: Double = ((pctGained * 0.0304) * 100.0).roundToInt() / 100.0,
    val trueEcoRangeAddedKm: Double = pctGained * 1.0,
    val wasOfflinePredicted: Boolean = false,
    val calibrationDeltaPct: Int? = null,
    val sessionSource: String = "Live BLE Charging"
)

enum class ChargingAlertType(val label: String) {
    NOTIFICATION("Notification Alert"),
    ALARM("Sound Alarm & Vibration")
}

data class ChargingAlertConfig(
    val isEnabled: Boolean = false,
    val targetPct: Int = 80,
    val alertType: ChargingAlertType = ChargingAlertType.NOTIFICATION,
    val isAlarmRinging: Boolean = false,
    val lastAlertedPct: Int? = null,
    val lastAlertTimestampMs: Long? = null
)

/**
 * Offline / Disconnected "Last Seen" Scooter Snapshot persisted in local storage.
 */
data class LastSeenScooterState(
    val timestampMs: Long,
    val batteryPct: Int,
    val odometerKm: Double,
    val clusterRangeKm: Double,
    val tripAKm: Double,
    val tripBKm: Double,
    val locationName: String,
    val latitude: Double = 21.0922,
    val longitude: Double = 79.0560,
    val wasCharging: Boolean,
    val vehicleId: String,
    val needsCalibration: Boolean = false,
    val isOfflinePredictedCharging: Boolean = false
)

/**
 * Dedicated Caller ID Banner Relay State (`0x5B 0x43` / `createMobileToClusterArray` + 500ms `iQubeCallerDataSendTimer`).
 */
data class IncomingCallRelayState(
    val isRinging: Boolean = false,
    val fullCallerName: String = "Rahul Sharma",
    val extractedFirstName: String = "Rahul",
    val clusterFormattedText: String = "Rahul",
    val callStateByte: Int = IqubeBleSpec.CALL_STATE_ENDED.toInt(),
    val packetsTransmittedCount: Int = 0,
    val ringStartTimeMs: Long? = null
)

/**
 * True Range Calculation Output (Rolling 3-Trip + Live Trip Efficiency vs. Scooter Cluster BMS DTE).
 */
data class TrueRangeCalculation(
    val trueEcoRangeKm: Double?,
    val truePowerRangeKm: Double?,
    val ecoKmPerPct: Double = IqubeBleSpec.DEFAULT_ECO_KM_PER_PCT,
    val powerKmPerPct: Double = IqubeBleSpec.DEFAULT_POWER_KM_PER_PCT,
    val totalDistanceLearnedKm: Double = 0.0,
    val totalBatteryUsedLearnedPct: Int = 0,
    val contributingTripsCount: Int = 0,
    val isUsingLearnedEfficiency: Boolean = false,
    val calculationSourceLabel: String = "Default Baseline (Eco 1.00 km/% • Power 0.75 km/%)"
)

data class ActiveTripSession(
    val startTimeMs: Long,
    val startLocationName: String,
    val startLatitude: Double,
    val startLongitude: Double,
    val startOdometerKm: Double?,
    val startBatteryPct: Int?,
    val speedSamplesSum: Long = 0L,
    val speedSamplesCount: Int = 0,
    val peakSpeedKmh: Int = 0,
    val dominantRideMode: RideMode = RideMode.ECO,
    val ecoDistanceKm: Double = 0.0,
    val powerDistanceKm: Double = 0.0,
    val ecoBatteryUsedPct: Int = 0,
    val powerBatteryUsedPct: Int = 0,
    val lastTrackedBatteryPct: Int? = null,
    val lastTrackedOdometerKm: Double? = null
)

data class SavedTripRecord(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSeconds: Long,
    val startLocationName: String,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLocationName: String,
    val endLatitude: Double,
    val endLongitude: Double,
    val distanceKm: Double,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val startBatteryPct: Int,
    val endBatteryPct: Int,
    val batteryUsedPct: Int,
    val peakSpeedKmh: Int,
    val avgSpeedKmh: Double,
    val rideMode: RideMode,
    val efficiencyKmPerPct: Double,
    val disconnectReason: String,
    val ecoDistanceKm: Double = 0.0,
    val powerDistanceKm: Double = 0.0,
    val ecoBatteryUsedPct: Int = 0,
    val powerBatteryUsedPct: Int = 0
)

data class QueuedWriteCommand(
    val id: Long,
    val label: String,
    val payload: ByteArray,
    val enqueuedAtMs: Long,
    val status: WriteCommandStatus = WriteCommandStatus.QUEUED,
    val ackLatencyMs: Long? = null,
    val errorDetail: String? = null
)

enum class WriteCommandStatus {
    QUEUED,
    AWAITING_ACK,
    ACK_RECEIVED,
    TIMEOUT_500MS,
    REJECTED_FRAMING,
    DISCONNECTED_ERROR
}

data class LastParkedLocation(
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val batteryPercentAtPark: Int?,
    val rangeKmAtPark: Double?,
    val odometerKmAtPark: Double?
)

data class GpsBleFusionSample(
    val timestampMs: Long,
    val wheelSpeedKmh: Int?,
    val gpsSpeedKmh: Double,
    val deltaKmh: Double?,
    val batteryPercent: Int?,
    val latitude: Double,
    val longitude: Double,
    val localityName: String
)

fun ByteArray.toHexString(separator: String = " "): String =
    joinToString(separator) { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

fun ByteArray.toAsciiPreview(): String =
    map { b ->
        val code = b.toInt() and 0xFF
        if (code in 32..126) code.toChar() else '.'
    }.joinToString("")
