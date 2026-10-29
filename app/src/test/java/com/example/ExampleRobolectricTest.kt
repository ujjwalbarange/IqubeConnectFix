package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.example.ble.GoogleMapsNotificationListenerService
import com.example.ble.IqubeBleManager
import com.example.model.BleConnectionPhase
import com.example.model.TurnTypeConstant
import com.example.tile.IqubeQuickSettingsTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExampleRobolectricTest {

    @Test
    fun `read string from context and verify initial disconnected BLE state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("iQube Cockpit", appName)

        val bleManager = IqubeBleManager(context)
        assertEquals(BleConnectionPhase.DISCONNECTED, bleManager.connectionPhase.value)
        assertNull(bleManager.packetParser.telemetryState.value.batteryPercent)
        assertNull(bleManager.packetParser.telemetryState.value.speedKmh)
    }

    @Test
    fun `verify maneuver bitmap classifier for Turn Right, Turn Left, Roundabout U-Turn, U-Turn, and Straight`() {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // 1. 90-degree Turn Right (↱): vertical shaft on left, horizontal arm to right, arrowhead on top-right
        val rightBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(rightBmp).apply {
            drawLine(18f, 54f, 18f, 22f, strokePaint)
            drawLine(18f, 22f, 48f, 22f, strokePaint)
            drawLine(36f, 10f, 50f, 22f, strokePaint)
            drawLine(36f, 34f, 50f, 22f, strokePaint)
        }
        assertEquals(
            TurnTypeConstant.TURN_RIGHT,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(rightBmp)
        )

        // 2. 90-degree Turn Left (↰): vertical shaft on right, horizontal arm to left, arrowhead on top-left
        val leftBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(leftBmp).apply {
            drawLine(46f, 54f, 46f, 22f, strokePaint)
            drawLine(46f, 22f, 16f, 22f, strokePaint)
            drawLine(28f, 10f, 14f, 22f, strokePaint)
            drawLine(28f, 34f, 14f, 22f, strokePaint)
        }
        assertEquals(
            TurnTypeConstant.TURN_LEFT,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(leftBmp)
        )

        // 3. Roundabout U-Turn (Screenshot_20261029-001507): hollow circle at top + left entry stem + right exit stem with downward arrow
        val roundaboutUTurnBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(roundaboutUTurnBmp).apply {
            drawOval(RectF(18f, 8f, 46f, 34f), strokePaint)
            drawLine(26f, 32f, 26f, 54f, strokePaint)
            drawLine(38f, 32f, 38f, 54f, strokePaint)
            drawLine(28f, 44f, 38f, 56f, strokePaint)
            drawLine(48f, 44f, 38f, 56f, strokePaint)
        }
        assertEquals(
            "Roundabout U-Turn must classify as U_TURN, never TURN_RIGHT",
            TurnTypeConstant.U_TURN,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(roundaboutUTurnBmp)
        )

        // 4. Standard U-Turn (↶): top arch + right stem + left stem with downward arrowhead
        val uTurnBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(uTurnBmp).apply {
            drawArc(RectF(18f, 10f, 46f, 36f), 180f, 180f, false, strokePaint)
            drawLine(46f, 23f, 46f, 54f, strokePaint)
            drawLine(18f, 23f, 18f, 50f, strokePaint)
            drawLine(10f, 40f, 18f, 52f, strokePaint)
            drawLine(26f, 40f, 18f, 52f, strokePaint)
        }
        assertEquals(
            TurnTypeConstant.U_TURN,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(uTurnBmp)
        )

        // 5. Right-arrowhead U-Turn (`↷` as in Screenshot_20261029-201804: left vertical stem up, top arch, right vertical stem down with wide arrowhead)
        val rightUTurnBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(rightUTurnBmp).apply {
            drawArc(RectF(14f, 10f, 40f, 36f), 180f, 180f, false, strokePaint)
            drawLine(14f, 23f, 14f, 54f, strokePaint)
            drawLine(40f, 23f, 40f, 50f, strokePaint)
            drawLine(30f, 38f, 40f, 52f, strokePaint)
            drawLine(52f, 38f, 40f, 52f, strokePaint)
        }
        assertEquals(
            "Right-arrowhead U-Turn must classify as U_TURN, never ROUNDABOUT_LEFT (0x45)",
            TurnTypeConstant.U_TURN,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(rightUTurnBmp)
        )

        // 6. Roundabout Straight (Screenshot_20261029-201708: bottom entry stem + middle roundabout circle with dimmed right arc + top upward arrow)
        val dimRingPaint = Paint(strokePaint).apply {
            color = Color.argb(90, 255, 255, 255)
        }
        val roundaboutStraightBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(roundaboutStraightBmp).apply {
            drawLine(32f, 58f, 32f, 44f, strokePaint)
            drawArc(RectF(18f, 24f, 46f, 46f), 90f, 180f, false, strokePaint)
            drawArc(RectF(18f, 24f, 46f, 46f), 270f, 180f, false, dimRingPaint)
            drawLine(32f, 24f, 32f, 10f, strokePaint)
            drawLine(22f, 20f, 32f, 10f, strokePaint)
            drawLine(42f, 20f, 32f, 10f, strokePaint)
        }
        assertEquals(
            "Roundabout Straight must classify as ROUNDABOUT, never TURN_SHARPLY_LEFT",
            TurnTypeConstant.ROUNDABOUT,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(roundaboutStraightBmp)
        )

        // 7. Roundabout Left (Screenshot_20261029-201832: bottom-right entry stem + right roundabout loop + left horizontal exit arrow)
        val roundaboutLeftBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(roundaboutLeftBmp).apply {
            drawLine(44f, 58f, 44f, 44f, strokePaint)
            drawOval(RectF(32f, 22f, 56f, 44f), strokePaint)
            drawLine(32f, 33f, 10f, 33f, strokePaint)
            drawLine(20f, 21f, 8f, 33f, strokePaint)
            drawLine(20f, 45f, 8f, 33f, strokePaint)
        }
        assertEquals(
            "Roundabout Left must classify as ROUNDABOUT_LEFT",
            TurnTypeConstant.ROUNDABOUT_LEFT,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(roundaboutLeftBmp)
        )

        // 8. Continue Straight (↑): centered vertical shaft + top arrowhead
        val straightBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(straightBmp).apply {
            drawLine(32f, 54f, 32f, 12f, strokePaint)
            drawLine(18f, 26f, 32f, 10f, strokePaint)
            drawLine(46f, 26f, 32f, 10f, strokePaint)
        }
        assertEquals(
            TurnTypeConstant.CONTINUE_STRAIGHT,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(straightBmp)
        )

        // 9. Blank/unrecognized icon defaults safely to CONTINUE_STRAIGHT
        val blankBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertEquals(
            TurnTypeConstant.CONTINUE_STRAIGHT,
            GoogleMapsNotificationListenerService.classifyManeuverBitmap(blankBmp)
        )

        // 10. Slash-separated street name extraction ("Krida Square Rd / Medical College Rd / Untkhana Rd")
        val kridaStreet = GoogleMapsNotificationListenerService.extractTargetStreetOrLocation(
            title = "120 m",
            text = "Krida Square Rd / Medical College Rd / Untkhana Rd",
            subText = "1 min · 500 m · 00:15",
            extraStrings = listOf("Exit navigation")
        )
        assertEquals("Krida Sq Rd", kridaStreet)

        // 11. ETA timestamp filtering ("20:19 ETA", "14:44 ETA", "2019 ETA", "1444 ETA") & swapped title/text fields
        val swappedStreet = GoogleMapsNotificationListenerService.extractTargetStreetOrLocation(
            title = "towards Medical Rd",
            text = "60 m",
            subText = "2 min · 500 m · 20:19 ETA",
            extraStrings = listOf("20:19 ETA", "2019 ETA", "1444 ETA", "now", "Exit navigation")
        )
        assertEquals("Medical Rd", swappedStreet)
    }

    @Test
    fun `verify Quick Settings 2-line Battery and Range bitmap icon generation`() {
        val bmp = IqubeQuickSettingsTileService.createBatteryRangeTileBitmap(
            batteryPct = 98,
            rangeKm = 103,
            isCharging = false
        )
        assertEquals(128, bmp.width)
        assertEquals(128, bmp.height)

        var topPixels = 0
        var bottomPixels = 0
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val alpha = (bmp.getPixel(x, y) ushr 24) and 0xFF
                if (alpha > 100) {
                    if (y < 64) topPixels++ else bottomPixels++
                }
            }
        }
        assertTrue("Top half must contain rendered '98%' text pixels", topPixels > 50)
        assertTrue("Bottom half must contain rendered '103km' text pixels", bottomPixels > 50)
    }

    @Test
    fun `verify ManeuverExtractor two-stage pipeline, 27-command catalog, TripSummary, and 1s throttling`() {
        assertEquals(27, com.example.ble.ManeuverExtractor.COMMAND_CATALOG.size)

        // Stage 1 Regex Tests across the core actions & TripSummary middle-dot parsing
        val cmd1 = com.example.ble.ManeuverExtractor.extract(
            title = "250 m",
            text = "Turn left onto MG Road",
            subText = "12 min · 4.2 km · 10:45 PM"
        )
        assertEquals(com.example.ble.ManeuverAction.TURN, cmd1.action)
        assertEquals(com.example.ble.Direction.LEFT, cmd1.direction)
        assertEquals(com.example.ble.TurnModifier.NORMAL, cmd1.modifier)
        assertEquals("250 m", cmd1.distanceRemaining)
        assertEquals("12 min", cmd1.tripSummary?.duration)
        assertEquals("4.2 km", cmd1.tripSummary?.remainingDistance)
        assertEquals("10:45 PM", cmd1.tripSummary?.eta)

        // Highway exit with alphanumeric exitNumber ("14B")
        val exitCmd = com.example.ble.ManeuverExtractor.extract(
            title = "800 m",
            text = "Take exit 14B toward Airport Rd",
            subText = "8 min · 6.0 km · 11:00 AM"
        )
        assertEquals(com.example.ble.ManeuverAction.HIGHWAY_EXIT, exitCmd.action)
        assertEquals(com.example.ble.Direction.RIGHT, exitCmd.direction)
        assertEquals("14B", exitCmd.exitNumber)
        assertEquals(15, exitCmd.commandId)

        // Roundabout with numeric exit index (2nd exit)
        val roundaboutCmd = com.example.ble.ManeuverExtractor.extract(
            title = "150 m",
            text = "At the roundabout, take the 2nd exit onto Broadway",
            subText = "5 min · 1.8 km · 10:15 AM"
        )
        assertEquals(com.example.ble.ManeuverAction.ROUNDABOUT, roundaboutCmd.action)
        assertEquals(2, roundaboutCmd.roundaboutExit)
        assertEquals(19, roundaboutCmd.commandId)

        // Sharp & Slight turns
        val sharpRight = com.example.ble.ManeuverExtractor.extract("90 m", "Sharp right onto Service Rd")
        assertEquals(com.example.ble.ManeuverAction.TURN, sharpRight.action)
        assertEquals(com.example.ble.Direction.RIGHT, sharpRight.direction)
        assertEquals(com.example.ble.TurnModifier.SHARP, sharpRight.modifier)

        val slightLeft = com.example.ble.ManeuverExtractor.extract("110 m", "Bear left onto Flyover")
        assertEquals(com.example.ble.ManeuverAction.TURN, slightLeft.action)
        assertEquals(com.example.ble.Direction.LEFT, slightLeft.direction)
        assertEquals(com.example.ble.TurnModifier.SLIGHT, slightLeft.modifier)

        // Keep & Fork
        val forkLeft = com.example.ble.ManeuverExtractor.extract("300 m", "At the fork, stay left toward Airport")
        assertEquals(com.example.ble.ManeuverAction.FORK, forkLeft.action)
        assertEquals(com.example.ble.Direction.LEFT, forkLeft.direction)

        val keepRight = com.example.ble.ManeuverExtractor.extract("400 m", "Keep right toward City Center")
        assertEquals(com.example.ble.ManeuverAction.KEEP, keepRight.action)
        assertEquals(com.example.ble.Direction.RIGHT, keepRight.direction)

        // U-Turn & Arrival & Reroute
        val uTurn = com.example.ble.ManeuverExtractor.extract("60 m", "Make a U-turn onto NH-44")
        assertEquals(com.example.ble.ManeuverAction.U_TURN, uTurn.action)
        assertEquals(com.example.ble.TurnModifier.U_TURN, uTurn.modifier)

        val arriveLeft = com.example.ble.ManeuverExtractor.extract("10 m", "Your destination is on the left")
        assertEquals(com.example.ble.ManeuverAction.ARRIVE, arriveLeft.action)
        assertEquals(com.example.ble.Direction.LEFT, arriveLeft.direction)

        val reroute = com.example.ble.ManeuverExtractor.extract("", "Rerouting...")
        assertEquals(com.example.ble.ManeuverAction.REROUTE, reroute.action)

        // Stage 2 (Visual Icon Fallback when EXTRA_TEXT has only a street name like "Munje Chowk" or "Ghat Rd"):
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val munjeRightBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(munjeRightBmp).apply {
            drawLine(18f, 54f, 18f, 22f, strokePaint)
            drawLine(18f, 22f, 48f, 22f, strokePaint)
            drawLine(36f, 10f, 50f, 22f, strokePaint)
            drawLine(36f, 34f, 50f, 22f, strokePaint)
        }
        val munjeRightCmd = com.example.ble.ManeuverExtractor.extract(
            title = "80 m",
            text = "Munje Chowk",
            largeIconBitmap = munjeRightBmp
        )
        assertEquals(com.example.ble.ManeuverAction.TURN, munjeRightCmd.action)
        assertEquals(com.example.ble.Direction.RIGHT, munjeRightCmd.direction)
        assertEquals(com.example.ble.TurnModifier.NORMAL, munjeRightCmd.modifier)

        val fillPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }

        // Left-heavy (>62% pixels on left side) -> Direction.LEFT
        val left24 = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(left24).drawRect(2f, 4f, 11f, 20f, fillPaint)
        val stage2Left = com.example.ble.ManeuverExtractor.extract(
            title = "200 m",
            text = "MG Road",
            largeIconBitmap = left24
        )
        assertEquals(com.example.ble.ManeuverAction.TURN, stage2Left.action)
        assertEquals(com.example.ble.Direction.LEFT, stage2Left.direction)

        // Right-heavy (>62% pixels on right side) -> Direction.RIGHT
        val right24 = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(right24).drawRect(13f, 4f, 22f, 20f, fillPaint)
        val stage2Right = com.example.ble.ManeuverExtractor.extract(
            title = "200 m",
            text = "Ghat Rd",
            largeIconBitmap = right24
        )
        assertEquals(com.example.ble.ManeuverAction.TURN, stage2Right.action)
        assertEquals(com.example.ble.Direction.RIGHT, stage2Right.direction)

        // Bottom-heavy density -> ManeuverAction.U_TURN
        val bottom24 = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(bottom24).drawRect(4f, 13f, 20f, 22f, fillPaint)
        val stage2UTurn = com.example.ble.ManeuverExtractor.extract(
            title = "120 m",
            text = "MG Road",
            largeIconBitmap = bottom24
        )
        assertEquals(com.example.ble.ManeuverAction.U_TURN, stage2UTurn.action)

        // Balanced top-heavy density -> Direction.STRAIGHT
        val topBalanced24 = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(topBalanced24).drawRect(6f, 2f, 18f, 11f, fillPaint)
        val stage2Straight = com.example.ble.ManeuverExtractor.extract(
            title = "500 m",
            text = "MG Road",
            largeIconBitmap = topBalanced24
        )
        assertEquals(com.example.ble.ManeuverAction.CONTINUE_STRAIGHT, stage2Straight.action)
        assertEquals(com.example.ble.Direction.STRAIGHT, stage2Straight.direction)

        // Requirement: Verify 1-second (1000ms) default countdown throttling vs immediate high-priority maneuver change
        GoogleMapsNotificationListenerService.handleNavigationStopped("Reset for test")
        val beforeCount = GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount

        // T=1000ms: Initial Turn Left at 300m -> Immediate dispatch (#1)
        GoogleMapsNotificationListenerService.processExtractedNotificationSnapshot(
            isOngoing = true,
            category = android.app.Notification.CATEGORY_NAVIGATION,
            snapshot = GoogleMapsNotificationListenerService.NotificationExtrasSnapshot(
                title = "300 m",
                text = "Turn left onto MG Road",
                subText = "5 min · 1.2 km · 10:15 AM",
                bigText = ""
            ),
            nowMs = 1_000L
        )
        assertEquals(beforeCount + 1, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)

        // T=1500ms (+0.5s < 1.0s): Same maneuver & street, distance ticks to 280m -> Throttled!
        GoogleMapsNotificationListenerService.processExtractedNotificationSnapshot(
            isOngoing = true,
            category = android.app.Notification.CATEGORY_NAVIGATION,
            snapshot = GoogleMapsNotificationListenerService.NotificationExtrasSnapshot(
                title = "280 m",
                text = "Turn left onto MG Road",
                subText = "5 min · 1.2 km · 10:15 AM",
                bigText = ""
            ),
            nowMs = 1_500L
        )
        assertEquals(beforeCount + 1, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)

        // T=2100ms (+1.1s >= 1.0s): Routine countdown tick to 220m after 1s window -> Dispatched (#2)
        GoogleMapsNotificationListenerService.processExtractedNotificationSnapshot(
            isOngoing = true,
            category = android.app.Notification.CATEGORY_NAVIGATION,
            snapshot = GoogleMapsNotificationListenerService.NotificationExtrasSnapshot(
                title = "220 m",
                text = "Turn left onto MG Road",
                subText = "5 min · 1.1 km · 10:15 AM",
                bigText = ""
            ),
            nowMs = 2_100L
        )
        assertEquals(beforeCount + 2, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)

        // T=2400ms (+0.3s < 1.0s): High-priority direction change to Turn Right -> Immediate dispatch (#3)!
        GoogleMapsNotificationListenerService.processExtractedNotificationSnapshot(
            isOngoing = true,
            category = android.app.Notification.CATEGORY_NAVIGATION,
            snapshot = GoogleMapsNotificationListenerService.NotificationExtrasSnapshot(
                title = "150 m",
                text = "Turn right onto 100 Feet Rd",
                subText = "4 min · 0.9 km · 10:14 AM",
                bigText = ""
            ),
            nowMs = 2_400L
        )
        assertEquals(beforeCount + 3, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)
    }

    @Test
    fun `verify offline predicted charging progression, needs calibration status, charging alarm while disconnected, and interval analysis`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bleManager = IqubeBleManager(context)

        // 1. Configure charging alert for 44%
        bleManager.setChargingAlertEnabled(true)
        bleManager.setChargingAlertTargetPct(44)

        // 2. Log a sample historical charging session (38% -> 82%) and verify interval learning
        bleManager.tripRepository.clearAllChargingSessions()
        bleManager.tripRepository.logSampleChargingSession()
        val sessions = bleManager.tripRepository.savedChargingSessions.value
        assertEquals(1, sessions.size)
        assertTrue(sessions.first().cc0To80PctGained > 0)
        assertTrue(sessions.first().estimatedKwhAdded > 1.0)

        // 3. Simulate disconnecting while charging at 42% (e.g. scooter turned off while plugged in)
        bleManager.simulateDisconnectWhileCharging()

        val offlineState = com.example.ble.TrueRangeAndTripRepository.computeChargingMonitorState(
            isCharging = true,
            currentBatteryPct = 42,
            clusterRangeKm = 44.1,
            sessionStartBatteryPct = 40,
            liveStepDurationsSec = emptyList(),
            savedChargingSessions = sessions,
            isDisconnectedPredicted = true,
            needsCalibration = true,
            calibrationStatusLabel = "⚠️ Needs Calibration (Predicted while Disconnected)",
            disconnectStartBatteryPct = 42
        )
        assertTrue("Charging section must remain active when disconnected while charging", offlineState.isCharging)
        assertTrue("Must flag isDisconnectedPredicted", offlineState.isDisconnectedPredicted)
        assertTrue("Must flag needsCalibration", offlineState.needsCalibration)
        assertTrue(
            "Calibration label must mention Needs Calibration",
            offlineState.calibrationStatusLabel.contains("Needs Calibration")
        )
        assertTrue("True 0->80% seconds must be positive", offlineState.true0To80TotalSeconds > 0)
        assertTrue("True 0->100% seconds must be greater than 0->80%", offlineState.true0To100TotalSeconds > offlineState.true0To80TotalSeconds)
        assertTrue("Charging insight text must be populated", offlineState.chargingInsightText.isNotBlank())

        // 4. Advance offline predicted charging from 42% -> 43% -> 44% and verify target alarm triggers even while disconnected
        bleManager.simulateLiveOnePercentChargingStep() // 42% -> 43%
        bleManager.simulateLiveOnePercentChargingStep() // 43% -> 44% (reaches targetPct = 44%)
        assertEquals(
            "Charging target alert must trigger at 44% even while scooter is turned off / disconnected",
            44,
            bleManager.chargingAlertConfig.value.lastAlertedPct
        )
        assertEquals(
            "LastSeenState must reflect predicted 44% and needsCalibration",
            44,
            bleManager.lastSeenState.value?.batteryPct
        )
        assertEquals(true, bleManager.lastSeenState.value?.needsCalibration)

        // 5. Calibrate battery SoC manually to 45% and verify calibration clears needsCalibration flag
        bleManager.calibrateOfflineBatterySoc(45)
        assertEquals(45, bleManager.lastSeenState.value?.batteryPct)
        bleManager.stopOfflinePredictedCharging()
        assertEquals(false, bleManager.lastSeenState.value?.isOfflinePredictedCharging)
    }
}



