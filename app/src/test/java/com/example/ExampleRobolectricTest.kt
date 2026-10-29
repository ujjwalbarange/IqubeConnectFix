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
}

