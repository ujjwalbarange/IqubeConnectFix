package com.example.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.ble.IqubeBleManager
import com.example.model.BleConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Android Quick Settings (Control Center) App Tile for glanceable TVS iQube battery SoC and range.
 * Dynamically renders a 2-line icon bitmap showing Battery % on top and Range (km) on bottom
 * (just like a live network speed tile), replacing the static Bluetooth symbol.
 * Tapping opens the app dashboard.
 */
class IqubeQuickSettingsTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val bleManager = IqubeBleManager.getInstance(applicationContext)

        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            combine(
                bleManager.connectionPhase,
                bleManager.packetParser.telemetryState,
                bleManager.lastSeenState,
                bleManager.trueRangeState
            ) { phase, tele, lastSeen, trueRange ->
                TileSnapshot(phase, tele, lastSeen, trueRange)
            }.collect { snapshot ->
                renderTile(snapshot)
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        super.onStopListening()
    }

    private fun renderTile(snapshot: TileSnapshot) {
        val tile = qsTile ?: return

        val isConnected = snapshot.phase in listOf(
            BleConnectionPhase.STREAMING_ACTIVE,
            BleConnectionPhase.REPLAYING_RIDING_LOG,
            BleConnectionPhase.REPLAYING_CHARGING_LOG
        )
        val isConnecting = snapshot.phase in listOf(
            BleConnectionPhase.SCANNING,
            BleConnectionPhase.CONNECTING_GATT,
            BleConnectionPhase.DISCOVERING_SERVICES,
            BleConnectionPhase.ENABLING_CCCD,
            BleConnectionPhase.NEGOTIATING_MTU,
            BleConnectionPhase.SENDING_HANDSHAKE
        )

        val tele = snapshot.tele
        val lastSeen = snapshot.lastSeen
        val trueRange = snapshot.trueRange

        val batteryPct = (tele.batteryPercent ?: lastSeen?.batteryPct ?: 93).coerceIn(0, 100)
        val clusterRangeKm = (tele.clusterRangeKm ?: lastSeen?.clusterRangeKm ?: 98.0).coerceAtLeast(0.0)
        val roundedRangeKm = clusterRangeKm.roundToInt()
        val trueEcoKm = trueRange.trueEcoRangeKm ?: (batteryPct * trueRange.ecoKmPerPct)
        val isCharging = isConnected && tele.isCharging

        // Render 2-line dynamic bitmap icon ("98%" on top, "103km" on bottom) like the network speed tile
        val bitmapIcon = createBatteryRangeTileBitmap(
            batteryPct = batteryPct,
            rangeKm = roundedRangeKm,
            isCharging = isCharging
        )
        tile.icon = Icon.createWithBitmap(bitmapIcon)

        when {
            isCharging -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "⚡ $batteryPct% • $roundedRangeKm km"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = String.format(Locale.US, "Charging • %d km DTE", roundedRangeKm)
                }
            }
            isConnected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "$batteryPct% • $roundedRangeKm km"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val spd = tele.speedKmh ?: 0
                    tile.subtitle = if (spd > 0) {
                        String.format(Locale.US, "%d km/h • %d km", spd, roundedRangeKm)
                    } else {
                        String.format(Locale.US, "True Eco: %.0f km", trueEcoKm)
                    }
                }
            }
            isConnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "$batteryPct% • $roundedRangeKm km"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connecting..."
                }
            }
            else -> {
                // Keep STATE_ACTIVE so compact icon-only Control Center tiles highlight the % and km crisply
                tile.state = Tile.STATE_ACTIVE
                tile.label = "$batteryPct% • $roundedRangeKm km"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val loc = lastSeen?.locationName?.takeIf { it.isNotBlank() } ?: "Parked"
                    tile.subtitle = String.format(Locale.US, "True: %.0f km • %s", trueEcoKm, loc)
                }
            }
        }

        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            runCatching { startActivity(intent) }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, IqubeQuickSettingsTileService::class.java)
                )
            }
        }

        /**
         * Generates a high-contrast 2-line transparent [Bitmap] icon showing:
         * - Line 1: Battery percentage (e.g., `"98%"` or `"⚡98%"`)
         * - Line 2: Range in km (e.g., `"103km"`)
         * Designed to match compact Control Center / Quick Settings tiles (like Network Speed `3\nKB/s`).
         */
        fun createBatteryRangeTileBitmap(
            batteryPct: Int,
            rangeKm: Int,
            isCharging: Boolean = false
        ): Bitmap {
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val topText = if (isCharging) "⚡${batteryPct}%" else "${batteryPct}%"
            val bottomText = "${rangeKm}km"

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            // Fit top line (Battery %) cleanly into the upper half
            var topSize = 52f
            paint.textSize = topSize
            val maxAllowedWidth = size * 0.94f
            while (paint.measureText(topText) > maxAllowedWidth && topSize > 26f) {
                topSize -= 2f
                paint.textSize = topSize
            }
            val topMetrics = paint.fontMetrics
            val topCenterY = size * 0.33f
            val topBaselineY = topCenterY - (topMetrics.ascent + topMetrics.descent) / 2f
            canvas.drawText(topText, size / 2f, topBaselineY, paint)

            // Fit bottom line (Range km) cleanly into the lower half
            var bottomSize = 40f
            paint.textSize = bottomSize
            while (paint.measureText(bottomText) > maxAllowedWidth && bottomSize > 22f) {
                bottomSize -= 2f
                paint.textSize = bottomSize
            }
            val bottomMetrics = paint.fontMetrics
            val bottomCenterY = size * 0.74f
            val bottomBaselineY = bottomCenterY - (bottomMetrics.ascent + bottomMetrics.descent) / 2f
            canvas.drawText(bottomText, size / 2f, bottomBaselineY, paint)

            return bitmap
        }
    }
}

private data class TileSnapshot(
    val phase: BleConnectionPhase,
    val tele: com.example.model.TelemetryState,
    val lastSeen: com.example.model.LastSeenScooterState?,
    val trueRange: com.example.model.TrueRangeCalculation
)
