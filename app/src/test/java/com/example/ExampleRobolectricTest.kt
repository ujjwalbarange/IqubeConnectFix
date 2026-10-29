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

        // 5. Continue Straight (↑): centered vertical shaft + top arrowhead
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

        // 6. Slash-separated street name extraction ("Krida Square Rd / Medical College Rd / Untkhana Rd")
        val kridaStreet = GoogleMapsNotificationListenerService.extractTargetStreetOrLocation(
            title = "120 m",
            text = "Krida Square Rd / Medical College Rd / Untkhana Rd",
            subText = "1 min · 500 m · 00:15",
            extraStrings = listOf("Exit navigation")
        )
        assertEquals("Krida Sq Rd", kridaStreet)
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
    fun `verify ManeuverExtractor two-stage pipeline, 27-command catalog, TripSummary, and 3s throttling`() {
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

        // Stage 2 (24x24 Visual Icon Fallback when EXTRA_TEXT has only a street name "MG Road"):
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
            text = "MG Road",
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

        // Requirement 3: Verify 3-second countdown throttling vs immediate high-priority maneuver change
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
            nowMs = 1_000L,
            countdownThrottleMs = 3_000L
        )
        assertEquals(beforeCount + 1, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)

        // T=2000ms (+1.0s < 3.0s): Same maneuver & street, distance ticks to 280m -> Throttled!
        GoogleMapsNotificationListenerService.processExtractedNotificationSnapshot(
            isOngoing = true,
            category = android.app.Notification.CATEGORY_NAVIGATION,
            snapshot = GoogleMapsNotificationListenerService.NotificationExtrasSnapshot(
                title = "280 m",
                text = "Turn left onto MG Road",
                subText = "5 min · 1.2 km · 10:15 AM",
                bigText = ""
            ),
            nowMs = 2_000L,
            countdownThrottleMs = 3_000L
        )
        assertEquals(beforeCount + 1, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)

        // T=4500ms (+3.5s >= 3.0s): Routine countdown tick to 220m after 3s window -> Dispatched (#2)
        GoogleMapsNotificationListenerService.processExtractedNotificationSnapshot(
            isOngoing = true,
            category = android.app.Notification.CATEGORY_NAVIGATION,
            snapshot = GoogleMapsNotificationListenerService.NotificationExtrasSnapshot(
                title = "220 m",
                text = "Turn left onto MG Road",
                subText = "5 min · 1.1 km · 10:15 AM",
                bigText = ""
            ),
            nowMs = 4_500L,
            countdownThrottleMs = 3_000L
        )
        assertEquals(beforeCount + 2, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)

        // T=5000ms (+0.5s < 3.0s): High-priority direction change to Turn Right -> Immediate dispatch (#3)!
        GoogleMapsNotificationListenerService.processExtractedNotificationSnapshot(
            isOngoing = true,
            category = android.app.Notification.CATEGORY_NAVIGATION,
            snapshot = GoogleMapsNotificationListenerService.NotificationExtrasSnapshot(
                title = "150 m",
                text = "Turn right onto 100 Feet Rd",
                subText = "4 min · 0.9 km · 10:14 AM",
                bigText = ""
            ),
            nowMs = 5_000L,
            countdownThrottleMs = 3_000L
        )
        assertEquals(beforeCount + 3, GoogleMapsNotificationListenerService.listenerState.value.dispatchedUpdateCount)
    }
}


