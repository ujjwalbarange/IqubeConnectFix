package com.example

import com.example.ble.HciLogReplayDataset
import com.example.ble.IqubeCommandDispatcher
import com.example.ble.IqubePacketParser
import com.example.ble.TrueRangeAndTripRepository
import com.example.model.IqubeBleSpec
import com.example.model.RideMode
import com.example.model.SavedTripRecord
import com.example.model.TurnTypeConstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ExampleUnitTest {

    @Test
    fun packetParser_startsWithNullTelemetryState_noMockData() {
        val parser = IqubePacketParser()
        val state = parser.telemetryState.value
        assertNull(state.batteryPercent)
        assertNull(state.speedKmh)
        assertNull(state.clusterRangeKm)
        assertNull(state.odometerKm)
        assertNull(state.tripAKm)
        assertNull(state.vehicleIdString)
    }

    @Test
    fun packetParser_decodesUnfiltered20ByteChargingCaptureFramesAndReassemblesSlices() {
        val parser = IqubePacketParser()

        // 1. Fragmented 0x5A 0x13 Battery + Cluster Range + Charging Flag (0x26 = 38%, 0x0190 = 40.0 km, Byte 5 = 0x06)
        val batteryFrame = HciLogReplayDataset.buildBatteryRangeFrame0x13(
            socPercent = 38,
            clusterRangeTenthsKm = 400, // 0x0190 = 40.0 km
            isCharging = true
        )
        assertEquals(0xFF.toByte(), batteryFrame[19])
        val slice1 = batteryFrame.copyOfRange(0, 8)
        val slice2 = batteryFrame.copyOfRange(8, 20)

        val firstResult = parser.ingestRawChunk(slice1, 1000L)
        assertTrue("Incomplete slice must wait for remaining bytes", firstResult.isEmpty())

        val secondResult = parser.ingestRawChunk(slice2, 1010L)
        assertEquals(1, secondResult.size)

        val stateAfter13 = parser.telemetryState.value
        assertEquals(38, stateAfter13.batteryPercent)
        assertEquals(40.0, stateAfter13.clusterRangeKm ?: 0.0, 0.01)
        assertEquals(0x06, stateAfter13.stateFlag0x13Byte5)
        // Strict 3-frame charging debounce: Frame 1 alone must NOT latch isCharging yet
        assertFalse(stateAfter13.isCharging)

        // Frame 2 (still < 3 frames):
        parser.ingestRawChunk(batteryFrame, 1100L)
        assertFalse(parser.telemetryState.value.isCharging)

        // Frame 3 (3 consecutive charging evaluations with speed == 0): latches isCharging = true
        parser.ingestRawChunk(batteryFrame, 1200L)
        assertTrue(parser.telemetryState.value.isCharging)

        // Strict Charging State Rule: Non-charging 0x13 frame (Byte 5 != 0x06) immediately clears isCharging
        parser.ingestRawChunk(
            HciLogReplayDataset.buildBatteryRangeFrame0x13(
                socPercent = 96,
                clusterRangeTenthsKm = 1010, // 0x03F2 = 101.0 km
                isCharging = false
            ),
            1500L
        )
        assertFalse(parser.telemetryState.value.isCharging)
        assertEquals(101.0, parser.telemetryState.value.clusterRangeKm ?: 0.0, 0.01)

        // 2. 0x5A 0x10 Speed + uint24 BE Total Hardware Odometer (Bytes 3..5 = 05 7B 54 = 359,252 = 35,925.2 km)
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedOdometerFrame0x10(0, 359252), 2000L)
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedOdometerFrame0x10(15, 359252), 3000L)
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedOdometerFrame0x10(32, 359252), 5200L)
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedOdometerFrame0x10(44, 359252), 6800L)

        val stateAfter10 = parser.telemetryState.value
        assertEquals(44, stateAfter10.speedKmh)
        assertEquals(44, stateAfter10.peakSpeedKmh)
        assertEquals(35925.2, stateAfter10.odometerKm ?: 0.0, 0.01)
        assertNotNull("0-30 km/h sprint timer should latch", stateAfter10.sprint0To30Sec)
        assertNotNull("0-40 km/h sprint timer should latch", stateAfter10.sprint0To40Sec)
        assertTrue(stateAfter10.integratedDistanceMeters > 0.0)

        // 3. 0x5A 0x7D Vehicle Model & ID String (Bytes 2..18 = "TVSiQUBEJI573")
        parser.ingestRawChunk(HciLogReplayDataset.buildVehicleIdentityFrame0x7D("TVSiQUBEJI573"), 7000L)
        val stateAfter7D = parser.telemetryState.value
        assertEquals("TVSiQUBEJI573", stateAfter7D.vehicleIdString)
        assertEquals(0x54.toByte(), stateAfter7D.latestFrame0x7D?.get(2))

        // 4. 0x5A 0x11 Trip A (00 00 03 = 0.3 km), Byte 5 = 0x01 (Charging), Byte 6 = 42% mirror, Trip B (00 00 0A = 1.0 km)
        // First reset speed to 0 km/h and send 0x13 with Byte 5 = 0x06 + 0x11 with Byte 5 = 0x01 for 3 consecutive evaluations
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedOdometerFrame0x10(0, 359252), 7500L)
        parser.ingestRawChunk(HciLogReplayDataset.buildBatteryRangeFrame0x13(42, 450, true), 7800L)
        parser.ingestRawChunk(
            HciLogReplayDataset.buildTripsAndChargingFrame0x11(
                tripATenthsKm = 3,
                tripBTenthsKm = 10,
                isChargingActive = true,
                socMirrorPercent = 42
            ),
            7900L
        )
        parser.ingestRawChunk(HciLogReplayDataset.buildBatteryRangeFrame0x13(42, 450, true), 8000L)
        val stateAfter11 = parser.telemetryState.value
        assertEquals(0.3, stateAfter11.tripAKm ?: 0.0, 0.01)
        assertEquals(1.0, stateAfter11.tripBKm ?: 0.0, 0.01)
        assertEquals(0x01, stateAfter11.chargingFlag0x11Byte5)
        assertEquals(42, stateAfter11.socMirror0x11)
        assertTrue(stateAfter11.isCharging)

        // 5. 0x5A 0x6B Handlebar Media Control Event (0x03 = Next Track)
        parser.ingestRawChunk(HciLogReplayDataset.buildMediaControlFrame0x6B(0x03), 8500L)
        val stateAfter6B = parser.telemetryState.value
        assertEquals(1, stateAfter6B.packetCount0x6B)
        assertEquals(0x03, stateAfter6B.lastMediaCommand?.code)
    }

    @Test
    fun commandDispatcher_enforcesMandatory0xFFTrailerAndSmaliPacketStructures() {
        val dispatcher = IqubeCommandDispatcher(
            scope = CoroutineScope(Dispatchers.Unconfined),
            performGattWrite = { true },
            onWriteAckTimeout = {}
        )

        // 1. Handshake [RU & Phone Battery/Signal Status Relay (0x5B, 0x52, 0x55, ...)
        val handshake = dispatcher.buildInitialHandshakeFrame()
        assertEquals(20, handshake.size)
        assertEquals(0xFF.toByte(), handshake[19])
        assertTrue(dispatcher.validateOutgoingFrame(handshake).isSuccess)

        val phoneStatus = dispatcher.buildPhoneStatusPacket(batteryPct = 88, signalBars = 4)
        assertEquals(0x5B.toByte(), phoneStatus[0])
        assertEquals(0x52.toByte(), phoneStatus[1])
        assertEquals(0x55.toByte(), phoneStatus[2])
        assertEquals(88.toByte(), phoneStatus[3])
        assertEquals(4.toByte(), phoneStatus[4])
        assertEquals(0x01.toByte(), phoneStatus[5])
        assertEquals(0xFF.toByte(), phoneStatus[19])
        assertTrue(dispatcher.validateOutgoingFrame(phoneStatus).isSuccess)

        // Verify ~20% phone battery encodes 1/4 bars instead of hardcoded 4/4 bars
        val lowBattPhoneStatus = dispatcher.buildPhoneStatusPacket(batteryPct = 20, signalBars = 4)
        assertEquals(20.toByte(), lowBattPhoneStatus[3])
        assertEquals(1.toByte(), lowBattPhoneStatus[4]) // 1/4 battery bars at 20%
        assertEquals(4.toByte(), lowBattPhoneStatus[6]) // 4/4 cellular bars

        // 2. Clock & Mobile Status Packet [J (BlePacketsProvider.sendIQubeMobileData: Byte[2]=(sig<<4)|batt, Byte[6..9]=12h HH:mm:ss AM/PM, Byte[10]=missedCalls)
        val heartbeat = dispatcher.buildClockSyncPacket(batteryPct = 20, signalBars = 4, missedCallsCount = 0, activeCallState = 0)
        assertEquals(20, heartbeat.size)
        assertEquals(0x41.toByte(), heartbeat[2]) // (4 << 4) | 1 = 0x41 (4/4 sig bars, 1/4 batt bars at 20%)
        assertEquals(0x78.toByte(), heartbeat[3])
        assertEquals(0x81.toByte(), heartbeat[4])
        assertEquals(0x02.toByte(), heartbeat[5])
        assertTrue(heartbeat[6] in 1..12) // 12-hour clock hour (1..12)
        assertTrue(heartbeat[7] in 0..59) // minute
        assertTrue(heartbeat[8] in 0..59) // second
        assertTrue(heartbeat[9].toInt() == 0 || heartbeat[9].toInt() == 1) // 0=PM, 1=AM
        assertEquals(0.toByte(), heartbeat[10]) // missedCallsCount = 0 (does NOT turn on missed call icon!)
        assertEquals(0.toByte(), heartbeat[12]) // activeCallState = 0
        assertEquals(0xFF.toByte(), heartbeat[19])
        assertTrue(dispatcher.validateOutgoingFrame(heartbeat).isSuccess)

        val missedCallMobileData = dispatcher.buildClockSyncPacket(batteryPct = 85, signalBars = 4, missedCallsCount = 2, activeCallState = 0)
        assertEquals(0x44.toByte(), missedCallMobileData[2]) // 4/4 sig bars, 4/4 batt bars
        assertEquals(2.toByte(), missedCallMobileData[10])   // 2 missed calls turns on cluster missed call icon

        // 3. Verified Trip Controls & Vehicle Date Sync (createVehicleDataArray: 0x00=Trip A, 0x01=Trip B, 0x02=Reset)
        val switchTripA = dispatcher.buildTripControlFrame(IqubeBleSpec.SUBCMD_SWITCH_TRIP_A)
        val switchTripB = dispatcher.buildTripControlFrame(IqubeBleSpec.SUBCMD_SWITCH_TRIP_B)
        val resetActive = dispatcher.buildTripControlFrame(IqubeBleSpec.SUBCMD_RESET_ACTIVE_TRIP)
        assertTrue(dispatcher.validateOutgoingFrame(switchTripA).isSuccess)
        assertTrue(dispatcher.validateOutgoingFrame(switchTripB).isSuccess)
        assertTrue(dispatcher.validateOutgoingFrame(resetActive).isSuccess)
        assertEquals(0x00.toByte(), switchTripA[3])
        assertEquals(0x00.toByte(), switchTripA[12])
        assertEquals(0x02.toByte(), switchTripA[5])
        assertEquals(0x01.toByte(), switchTripA[14])
        assertTrue(switchTripA[9] in 1..31)  // DAY_OF_MONTH
        assertTrue(switchTripA[10] in 1..12) // MONTH
        assertEquals(0xFF.toByte(), switchTripA[19])
        assertEquals(0xFF.toByte(), switchTripB[19])
        assertEquals(0xFF.toByte(), resetActive[19])

        // 4. Live GPS Location Packet (0x5B 0x5C + up to 17 ASCII chars + Byte 19 = 0xFF)
        val locationPkt = dispatcher.buildClusterLocationPacket("Sonegaon")
        assertEquals(20, locationPkt.size)
        assertEquals(0x5B.toByte(), locationPkt[0])
        assertEquals(0x5C.toByte(), locationPkt[1])
        val encodedText = String(locationPkt, 2, 8, StandardCharsets.US_ASCII)
        assertEquals("Sonegaon", encodedText)
        assertEquals(0x00.toByte(), locationPkt[10])
        assertEquals(0xFF.toByte(), locationPkt[19])
        assertTrue(dispatcher.validateOutgoingFrame(locationPkt).isSuccess)

        // 5. Dedicated Caller ID Packet (0x5B 0x43 + "Rahul" + 0xFF) & Incoming/Outgoing/Missed Call States (0x5B 0x5D 0x43)
        val callPkt = dispatcher.buildCallerPacket0x43("Rahul Sharma")
        assertEquals(0x5B.toByte(), callPkt[0])
        assertEquals(0x43.toByte(), callPkt[1])
        val callText = String(callPkt, 2, 5, StandardCharsets.US_ASCII)
        assertEquals("Rahul", callText)
        assertEquals(0xFF.toByte(), callPkt[19])
        assertTrue(dispatcher.validateOutgoingFrame(callPkt).isSuccess)

        val missedBannerPkt = dispatcher.buildCallerPacket0x43("Miss: Rahul", extractFirstWordOnly = false)
        assertEquals("Miss: Rahul", String(missedBannerPkt, 2, 11, StandardCharsets.US_ASCII))
        assertTrue(dispatcher.validateOutgoingFrame(missedBannerPkt).isSuccess)

        val callStatePkt = dispatcher.buildCallStatePacket(IqubeBleSpec.CALL_STATE_RINGING)
        assertEquals(0x5B.toByte(), callStatePkt[0])
        assertEquals(0x5D.toByte(), callStatePkt[1])
        assertEquals(0x43.toByte(), callStatePkt[2])
        assertEquals(0x00.toByte(), callStatePkt[3])
        assertEquals(0xFF.toByte(), callStatePkt[19])
        assertTrue(dispatcher.validateOutgoingFrame(callStatePkt).isSuccess)

        val missedStatePkt = dispatcher.buildCallStatePacket(IqubeBleSpec.CALL_STATE_MISSED)
        assertEquals(0x03.toByte(), missedStatePkt[3])
        val outgoingStatePkt = dispatcher.buildCallStatePacket(IqubeBleSpec.CALL_STATE_OUTGOING)
        assertEquals(0x04.toByte(), outgoingStatePkt[3])

        // 6. Turn-by-Turn Navigation Sequence (0x5B 0x4F Instruction + 0x5A/0x5B 0x4E Binary Control Frame)
        val navInst0x4F = dispatcher.buildNavigationInstructionPacket0x4F("Turn Right")
        assertEquals(0x5B.toByte(), navInst0x4F[0])
        assertEquals(0x4F.toByte(), navInst0x4F[1])
        assertEquals("Turn Right", String(navInst0x4F, 2, 10, StandardCharsets.US_ASCII))
        assertEquals(0xFF.toByte(), navInst0x4F[19])
        assertTrue(dispatcher.validateOutgoingFrame(navInst0x4F).isSuccess)

        val navCtrl0x4E = dispatcher.buildNavigationControlPacket0x4E(
            turnCode = TurnTypeConstant.TURN_RIGHT.code,
            distanceMeters = 250,
            etaMinutes = 12,
            totalRemainingDistanceMeters = 1500,
            navActive = true,
            headerByte = IqubeBleSpec.HEADER_TELEMETRY
        )
        assertEquals(0x5A.toByte(), navCtrl0x4E[0])
        assertEquals(0x4E.toByte(), navCtrl0x4E[1])
        assertEquals(0x00.toByte(), navCtrl0x4E[2])
        assertEquals(250.toByte(), navCtrl0x4E[3]) // uint16 BE distance = 250m
        assertEquals(0x00.toByte(), navCtrl0x4E[4])
        assertEquals(12.toByte(), navCtrl0x4E[5])  // uint16 BE ETA minutes = 12 (never 0x5761 = 372h 49m!)
        assertEquals(0x03.toByte(), navCtrl0x4E[9]) // Byte[9] = pictogram (0x03 = Turn Right)
        assertEquals(0x01.toByte(), navCtrl0x4E[10])
        assertEquals(0x01.toByte(), navCtrl0x4E[11]) // navOnOFF = 1
        assertEquals(0xFF.toByte(), navCtrl0x4E[19])
        assertTrue(dispatcher.validateOutgoingFrame(navCtrl0x4E).isSuccess)

        val navPkt2 = dispatcher.buildNavigationStreetPacket2(TurnTypeConstant.U_TURN.code, "", distanceMeters = 150, etaMinutes = 8)
        assertEquals(0x5B.toByte(), navPkt2[0])
        assertEquals(0x4E.toByte(), navPkt2[1])
        assertEquals(150.toByte(), navPkt2[3])
        assertEquals(8.toByte(), navPkt2[5])
        assertEquals(0x06.toByte(), navPkt2[9]) // Byte[9] = 0x06 (U-Turn)
        assertEquals(0xFF.toByte(), navPkt2[19])
        assertTrue(dispatcher.validateOutgoingFrame(navPkt2).isSuccess)

        // 7. Music Track Title Banner (SONG_NAME_PACKET 0x5B 0x62) & Rider Greeting Name (0x5B 0x52)
        val musicPkt = dispatcher.buildMusicTrackPacket("Midnight City")
        assertEquals(0x5B.toByte(), musicPkt[0])
        assertEquals(0x62.toByte(), musicPkt[1])
        assertEquals("Midnight City", String(musicPkt, 2, 13, StandardCharsets.US_ASCII))
        assertEquals(0xFF.toByte(), musicPkt[19])
        assertTrue(dispatcher.validateOutgoingFrame(musicPkt).isSuccess)

        val riderPkt = dispatcher.buildRiderNamePacket("Aarav")
        assertEquals(0x5B.toByte(), riderPkt[0])
        assertEquals(0x52.toByte(), riderPkt[1])
        assertEquals("Aarav", String(riderPkt, 2, 5, StandardCharsets.US_ASCII))
        assertEquals(0xFF.toByte(), riderPkt[19])
        assertTrue(dispatcher.validateOutgoingFrame(riderPkt).isSuccess)

        // 8. Reject packet missing 0xFF at Byte 19
        val missingTrailer = locationPkt.copyOf()
        missingTrailer[19] = 0x00
        assertTrue(dispatcher.validateOutgoingFrame(missingTrailer).isFailure)
    }

    @Test
    fun chargingPredictorAndTrueRangeEngine_computeAccuratePredictions() {
        // 1. Tier 1 Default Charging Baseline (2h 45m for 0-80% = 123.75s per 1%, 80-100% taper = 180s per 1%)
        val defaultChg = TrueRangeAndTripRepository.computeChargingMonitorState(
            isCharging = true,
            currentBatteryPct = 40,
            clusterRangeKm = 42.5,
            sessionStartBatteryPct = 38,
            liveStepDurationsSec = emptyList(),
            savedChargingSessions = emptyList()
        )
        assertFalse(defaultChg.isUsingLiveStepTiming)
        assertEquals(123.75, defaultChg.activeSecondsPerPct, 0.01)
        assertEquals(4950L, defaultChg.remainingSecondsTo80)  // (80 - 40) * 123.75 = 4950s
        assertEquals(8550L, defaultChg.remainingSecondsTo100) // 4950s + (20 * 180s) = 8550s

        // 2. Tier 3 Live 1% Step Timing (115.7s per +1% = +31.1%/hr from charging capture)
        val liveChg = TrueRangeAndTripRepository.computeChargingMonitorState(
            isCharging = true,
            currentBatteryPct = 42,
            clusterRangeKm = 45.0,
            sessionStartBatteryPct = 38,
            liveStepDurationsSec = listOf(115.7),
            savedChargingSessions = emptyList()
        )
        assertTrue(liveChg.isUsingLiveStepTiming)
        assertEquals(115.7, liveChg.activeSecondsPerPct, 0.1)
        assertEquals(31.1, liveChg.chargingSpeedPctPerHour, 0.1)
        assertEquals(4397L, liveChg.remainingSecondsTo80)  // (80 - 42) * 115.7 = 4396.6 -> 4397s
        assertEquals(7997L, liveChg.remainingSecondsTo100) // 4396.6 + (20 * 180) = 7996.6 -> 7997s

        // 3. True Range Engine Baseline (1.00 km/% Eco, 0.75 km/% Power) & Learned
        val baselineRange = TrueRangeAndTripRepository.computeTrueRange(
            currentBatteryPct = 91,
            savedTrips = emptyList()
        )
        assertEquals(91.0, baselineRange.trueEcoRangeKm ?: 0.0, 0.05)
        assertEquals(68.3, baselineRange.truePowerRangeKm ?: 0.0, 0.05)

        val sampleTrip = SavedTripRecord(
            id = "t1",
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            durationSeconds = 600L,
            startLocationName = "Sonegaon",
            startLatitude = 21.09,
            startLongitude = 79.05,
            endLocationName = "Sitabuldi",
            endLatitude = 21.14,
            endLongitude = 79.08,
            distanceKm = 4.50,
            startOdometerKm = 35920.7,
            endOdometerKm = 35925.2,
            startBatteryPct = 95,
            endBatteryPct = 90,
            batteryUsedPct = 5,
            peakSpeedKmh = 48,
            avgSpeedKmh = 27.0,
            rideMode = RideMode.ECO,
            efficiencyKmPerPct = 0.90,
            disconnectReason = "Manual"
        )
        val learnedRange = TrueRangeAndTripRepository.computeTrueRange(
            currentBatteryPct = 90,
            savedTrips = listOf(sampleTrip)
        )
        assertTrue(learnedRange.isUsingLearnedEfficiency)
        assertEquals(81.0, learnedRange.trueEcoRangeKm ?: 0.0, 0.1)
    }
}
