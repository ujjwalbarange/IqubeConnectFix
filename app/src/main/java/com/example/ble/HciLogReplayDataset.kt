package com.example.ble

import com.example.model.IqubeBleSpec
import java.nio.charset.StandardCharsets

data class ChargingCaptureStep(
    val socPercent: Int,
    val clusterRangeTenthsKm: Int,
    val measuredStepDurationSec: Double?
)

/**
 * Reconstructs the verified unfiltered 20-byte binary frames (with `Byte 19 = 0xFF`) from:
 * 1. Riding Capture (`btsnooz_hci.log`):
 *    - `0x5A 0x10`: 5 throttle bursts (`11`, `20`, `10`, `52`, `14 km/h`), Total Odometer `05 7B 54` = `35,925.2 km`
 *    - `0x5A 0x13`: Battery SoC `91%` (`0x5B`), Cluster Range `03 C0` = `96.0 km`, `Byte 5 = 0x00`
 *    - `0x5A 0x11`: Trip A `0.3 km` (`00 00 03`), `Byte 5 = 0x00`, `Byte 6 = 91`, Trip B `1.0 km` (`00 00 0A`)
 *    - `0x5A 0x7D`: ASCII `"TVSiQUBEJI573"` in `Bytes 2..18`
 * 2. Unfiltered Charging Capture (`btsnoop_hci.log`, `4,865` packets):
 *    - `0x5A 0x10`: Wheel speed `0 km/h`, Total Odometer `05 7B 54` = `35,925.2 km`
 *    - `0x5A 0x13`: Battery SoC `38%..42%` (`0x26..0x2A`), Cluster Range `40.0 km..45.0 km` (`0x0190..0x01C2`), `Byte 5 = 0x06` (Charging Mode)
 *    - `0x5A 0x11`: Trip A `0.3 km` (`00 00 03`), `Byte 5 = 0x01` (Charging Active), `Byte 6 = SoC%`, Trip B `1.0 km` (`00 00 0A`)
 *    - `0x5A 0x7D`: ASCII `"TVSiQUBEJI573"` in `Bytes 2..18`
 * 3. Handlebar Media Control Event (`0x5A 0x6B`):
 *    - `Byte 2`: `0x00` Play, `0x01` Pause, `0x02` Toggle, `0x03` Next, `0x04` Prev, `0x05` Vol+, `0x06` Vol-
 */
object HciLogReplayDataset {

    const val CHARGING_CAPTURE_ODO_TENTHS_KM = 359252    // 0x057B54 = 35,925.2 km
    const val CHARGING_CAPTURE_TRIP_A_TENTHS_KM = 3      // 0x000003 = 0.3 km
    const val CHARGING_CAPTURE_TRIP_B_TENTHS_KM = 10     // 0x00000A = 1.0 km

    val CAPTURED_SPEED_PROFILE_KMH: IntArray = intArrayOf(
        // Burst 1: Peak 11 km/h
        0, 0, 3, 6, 9, 11, 11, 10, 7, 4, 1, 0,
        // Burst 2: Peak 20 km/h
        0, 4, 9, 14, 18, 20, 20, 19, 15, 10, 5, 0,
        // Burst 3: Peak 10 km/h
        0, 3, 7, 10, 10, 8, 5, 2, 0,
        // Burst 4: Full power sprint to 52 km/h (triggers 0-30 and 0-40 km/h sprint timers)
        0, 6, 13, 21, 28, 33, 38, 43, 48, 51, 52, 52, 50, 45, 38, 29, 19, 9, 0,
        // Burst 5: Peak 14 km/h cooldown maneuver
        0, 4, 9, 12, 14, 14, 11, 7, 3, 0
    )

    // Verified Charging SoC % steps from btsnoop_hci.log: 38% (40.0 km) -> 42% (45.0 km) at ~115.7s per +1%
    val CHARGING_CAPTURE_STEPS: List<ChargingCaptureStep> = listOf(
        ChargingCaptureStep(socPercent = 38, clusterRangeTenthsKm = 400, measuredStepDurationSec = null),
        ChargingCaptureStep(socPercent = 39, clusterRangeTenthsKm = 412, measuredStepDurationSec = 115.7),
        ChargingCaptureStep(socPercent = 40, clusterRangeTenthsKm = 425, measuredStepDurationSec = 114.8),
        ChargingCaptureStep(socPercent = 41, clusterRangeTenthsKm = 438, measuredStepDurationSec = 116.5),
        ChargingCaptureStep(socPercent = 42, clusterRangeTenthsKm = 450, measuredStepDurationSec = 115.8)
    )

    /**
     * `0x5A 0x10` (Speed + Total Hardware Odometer, 20 bytes ending in `0xFF`):
     * - `Byte 2` = `speedKmh`
     * - `Bytes 3..5` = `uint24 Big-Endian` Total Odometer in `0.1 km` (`359,252` = `0x057B54` = `35,925.2 km`)
     */
    fun buildSpeedOdometerFrame0x10(
        speedKmh: Int,
        totalOdometerTenthsKm: Int = CHARGING_CAPTURE_ODO_TENTHS_KM
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_SPEED_ODOMETER // 0x10
        frame[2] = (speedKmh and 0xFF).toByte()
        frame[3] = ((totalOdometerTenthsKm shr 16) and 0xFF).toByte()
        frame[4] = ((totalOdometerTenthsKm shr 8) and 0xFF).toByte()
        frame[5] = (totalOdometerTenthsKm and 0xFF).toByte()
        frame[11] = 0x05 // Cluster brightness level
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * `0x5A 0x13` (Battery SoC + Cluster Range + Charging Mode, 20 bytes ending in `0xFF`):
     * - `Byte 2` = `socPercent` (`0x26..0x2A` = `38%..42%`, or `0x5B` = `91%`)
     * - `Bytes 3..4` = `uint16 Big-Endian` Cluster Range in `0.1 km` (`0x0190` = `40.0 km` .. `0x01C2` = `45.0 km`)
     * - `Byte 5` = `0x06` when charging, `0x00` when riding
     */
    fun buildBatteryRangeFrame0x13(
        socPercent: Int = 38,
        clusterRangeTenthsKm: Int = 400, // 0x0190 = 40.0 km
        isCharging: Boolean = true
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_BATTERY_RANGE // 0x13
        frame[2] = (socPercent and 0xFF).toByte()
        frame[3] = ((clusterRangeTenthsKm shr 8) and 0xFF).toByte()
        frame[4] = (clusterRangeTenthsKm and 0xFF).toByte()
        frame[5] = if (isCharging) IqubeBleSpec.FLAG_0X13_CHARGING_MODE else 0x00
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * `0x5A 0x11` (Hardware Trip A, Trip B, & Charging Active Flag, 20 bytes ending in `0xFF`):
     * - `Bytes 2..4` = `uint24 Big-Endian` Trip A in `0.1 km` (`00 00 03` = `0.3 km`)
     * - `Byte 5` = `0x01` when Charging Active, `0x00` when Not Charging
     * - `Byte 6` = `socMirrorPercent` (`d[6]`)
     * - `Bytes 7..9` = `uint24 Big-Endian` Trip B in `0.1 km` (`00 00 0A` = `1.0 km`)
     */
    fun buildTripsAndChargingFrame0x11(
        tripATenthsKm: Int = CHARGING_CAPTURE_TRIP_A_TENTHS_KM,   // 00 00 03 = 0.3 km
        tripBTenthsKm: Int = CHARGING_CAPTURE_TRIP_B_TENTHS_KM,   // 00 00 0A = 1.0 km
        isChargingActive: Boolean = true,
        socMirrorPercent: Int = 38
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_TRIPS_CHARGING // 0x11
        // Bytes 2..4: Trip A uint24 BE
        frame[2] = ((tripATenthsKm shr 16) and 0xFF).toByte()
        frame[3] = ((tripATenthsKm shr 8) and 0xFF).toByte()
        frame[4] = (tripATenthsKm and 0xFF).toByte()
        // Byte 5: Charging Active Flag (0x01 = Charging Active)
        frame[5] = if (isChargingActive) IqubeBleSpec.FLAG_0X11_CHARGING_ACTIVE else 0x00
        // Byte 6: Battery SoC % mirror
        frame[6] = (socMirrorPercent and 0xFF).toByte()
        // Bytes 7..9: Trip B uint24 BE
        frame[7] = ((tripBTenthsKm shr 16) and 0xFF).toByte()
        frame[8] = ((tripBTenthsKm shr 8) and 0xFF).toByte()
        frame[9] = (tripBTenthsKm and 0xFF).toByte()
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * `0x5A 0x7D` (Vehicle Model & ID String, 20 bytes ending in `0xFF`):
     * - `Bytes 2..18` = ASCII `"TVSiQUBEJI573"`
     */
    fun buildVehicleIdentityFrame0x7D(
        vehicleId: String = IqubeBleSpec.DEFAULT_VEHICLE_ID
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_VEHICLE_ID // 0x7D
        val asciiBytes = vehicleId.take(17).toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(asciiBytes, 0, frame, 2, asciiBytes.size)
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * `0x5A 0x6B` (Cluster Handlebar Music Control Event, 20 bytes ending in `0xFF`):
     * - `Byte 2` = Music Command Code (`0x00`=Play, `0x01`=Pause, `0x02`=Toggle, `0x03`=Next, `0x04`=Prev, `0x05`=Vol+, `0x06`=Vol-)
     */
    fun buildMediaControlFrame0x6B(commandCode: Int): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_MEDIA_CONTROL // 0x6B
        frame[2] = (commandCode and 0xFF).toByte()
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }
}
