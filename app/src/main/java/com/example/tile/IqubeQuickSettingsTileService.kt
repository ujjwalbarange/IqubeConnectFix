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
import com.example.ble.IqubeSharedStorageManager
import com.example.model.BleConnectionPhase
import com.example.model.ClickAction
import com.example.model.TargetSurface
import com.example.model.TileMetricType
import com.example.model.WidgetCustomTemplate
import com.example.widget.CustomSurfaceCanvasRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Android Quick Settings (Control Center) App Tile for glanceable TVS iQube telemetry.
 * Supports full Studio customization of Top Line Parameter, Bottom Line Parameter, Subtitle Parameter,
 * Top Font Size, Bottom Font Size, Bold toggle, and optional Border Box.
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
                bleManager.trueRangeState,
                bleManager.chargingMonitorState,
                bleManager.activeTileTemplate
            ) { values ->
                val phase = values[0] as BleConnectionPhase
                val tele = values[1] as com.example.model.TelemetryState
                val lastSeen = values[2] as? com.example.model.LastSeenScooterState
                val trueRange = values[3] as com.example.model.TrueRangeCalculation
                val charging = values[4] as com.example.model.ChargingMonitorState
                val tileTemplate = values[5] as WidgetCustomTemplate
                TileSnapshot(phase, tele, lastSeen, trueRange, charging, tileTemplate)
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
        val tileConfig = snapshot.tileTemplate.tileConfig
        val liveSnapshot = CustomSurfaceCanvasRenderer.captureLiveSnapshot(applicationContext)

        // Render customizable Control Center Tile bitmap (user-configured Top/Bottom parameters & font sizes)
        val bitmapIcon = CustomSurfaceCanvasRenderer.renderControlCenterTileBitmap(
            config = tileConfig,
            snapshot = liveSnapshot
        )
        tile.icon = Icon.createWithBitmap(bitmapIcon)
        tile.state = Tile.STATE_ACTIVE

        // Tile Label (supports user's chosen topMetric + bottomMetric or custom text)
        val topLabelStr = CustomSurfaceCanvasRenderer.resolveTileMetricText(
            metric = tileConfig.topMetric,
            snapshot = liveSnapshot,
            customText = tileConfig.customTopText
        )
        val bottomLabelStr = if (tileConfig.bottomMetric != TileMetricType.NONE_SINGLE_LINE) {
            CustomSurfaceCanvasRenderer.resolveTileMetricText(
                metric = tileConfig.bottomMetric,
                snapshot = liveSnapshot,
                customText = tileConfig.customBottomText
            )
        } else ""
        tile.label = if (bottomLabelStr.isNotBlank()) "$topLabelStr • $bottomLabelStr" else topLabelStr

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                liveSnapshot.isDisconnectedPredicted -> "Needs Calibration"
                liveSnapshot.isConnecting -> if (liveSnapshot.needsCalibration) "Needs Calibration" else "Connecting..."
                tileConfig.subtitleMetric != TileMetricType.NONE_SINGLE_LINE -> {
                    val subVal = CustomSurfaceCanvasRenderer.resolveTileMetricText(
                        metric = tileConfig.subtitleMetric,
                        snapshot = liveSnapshot,
                        customText = tileConfig.customBottomText
                    )
                    if (liveSnapshot.isCharging) "Charging • $subVal" else subVal
                }
                else -> if (liveSnapshot.isCharging) {
                    "Charging • ${liveSnapshot.clusterRangeKm.roundToInt()} km"
                } else {
                    "True: ${liveSnapshot.trueEcoKm.roundToInt()} km"
                }
            }
        }

        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val activeTemplate = IqubeSharedStorageManager.getInstance(applicationContext)
            .loadActiveTemplateForSurface(TargetSurface.CONTROL_CENTER_TILE)
        if (activeTemplate.clickAction == ClickAction.TRIGGER_BLE_CONNECT) {
            val bleManager = IqubeBleManager.getInstance(applicationContext)
            bleManager.resetAutoReconnectWindowAndConnect()
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (activeTemplate.clickAction == ClickAction.OPEN_STUDIO_SANDBOX) {
                putExtra("NAV_DESTINATION", "studio_sandbox")
            } else if (activeTemplate.clickAction == ClickAction.OPEN_SAVED_TRIPS) {
                putExtra("NAV_DESTINATION", "saved_trips")
            }
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
         * Used as the status-bar small icon and default fallback.
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
    val trueRange: com.example.model.TrueRangeCalculation,
    val charging: com.example.model.ChargingMonitorState,
    val tileTemplate: WidgetCustomTemplate
)
