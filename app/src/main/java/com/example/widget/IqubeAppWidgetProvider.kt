package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.ble.IqubeBleManager
import com.example.model.BleConnectionPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Home Screen App Widget for TVS iQube Cockpit.
 * Displays live or last-seen battery SoC, DTE cluster range, learned True Range (Eco & Power),
 * connection status, and locality with a single tap to open the app.
 */
class IqubeAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, IqubeAppWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId)
            }
            com.example.tile.IqubeQuickSettingsTileService.requestTileUpdate(context)
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_iqube_cockpit)
            val bleManager = IqubeBleManager.getInstance(context)

            val phase = bleManager.connectionPhase.value
            val isConnected = phase == BleConnectionPhase.STREAMING_ACTIVE ||
                phase == BleConnectionPhase.REPLAYING_RIDING_LOG ||
                phase == BleConnectionPhase.REPLAYING_CHARGING_LOG

            val isConnecting = phase == BleConnectionPhase.SCANNING ||
                phase == BleConnectionPhase.CONNECTING_GATT ||
                phase == BleConnectionPhase.DISCOVERING_SERVICES ||
                phase == BleConnectionPhase.ENABLING_CCCD ||
                phase == BleConnectionPhase.NEGOTIATING_MTU ||
                phase == BleConnectionPhase.SENDING_HANDSHAKE

            val tele = bleManager.packetParser.telemetryState.value
            val lastSeen = bleManager.lastSeenState.value
            val trueRange = bleManager.trueRangeState.value
            val charging = bleManager.chargingMonitorState.value
            val isCharging = tele.isCharging || charging.isCharging

            val batteryPct = tele.batteryPercent
                ?: charging.currentBatteryPct
                ?: lastSeen?.batteryPct
                ?: 93
            val clusterRangeKm = tele.clusterRangeKm
                ?: charging.clusterRangeKm
                ?: lastSeen?.clusterRangeKm
                ?: 98.0
            val ecoTrue = trueRange.trueEcoRangeKm ?: (batteryPct * trueRange.ecoKmPerPct)
            val pwrTrue = trueRange.truePowerRangeKm ?: (batteryPct * trueRange.powerKmPerPct)

            val locality = bleManager.detectedLocalityName.value.ifBlank {
                lastSeen?.locationName ?: "Dhantoli"
            }

            // Connection Status Badge
            when {
                charging.isDisconnectedPredicted -> {
                    views.setTextViewText(R.id.widget_status_badge, "⚡ Pred • Calibrate")
                    views.setTextColor(R.id.widget_status_badge, Color.parseColor("#FFB300")) // Amber Warning
                }
                isCharging -> {
                    views.setTextViewText(R.id.widget_status_badge, "⚡ Charging")
                    views.setTextColor(R.id.widget_status_badge, Color.parseColor("#00E5FF")) // Electric Cyan
                }
                isConnected -> {
                    views.setTextViewText(R.id.widget_status_badge, "● Connected")
                    views.setTextColor(R.id.widget_status_badge, Color.parseColor("#00E676")) // Neon Mint
                }
                isConnecting -> {
                    views.setTextViewText(R.id.widget_status_badge, "⟳ Connecting")
                    views.setTextColor(R.id.widget_status_badge, Color.parseColor("#FFA000")) // Amber
                }
                charging.needsCalibration -> {
                    views.setTextViewText(R.id.widget_status_badge, "⚠️ Needs Cal")
                    views.setTextColor(R.id.widget_status_badge, Color.parseColor("#FFB300"))
                }
                else -> {
                    views.setTextViewText(R.id.widget_status_badge, "○ Disconnected")
                    views.setTextColor(R.id.widget_status_badge, Color.parseColor("#94A3B8")) // Slate
                }
            }

            val devName = bleManager.connectedDeviceName.value.ifBlank { "TVSiQUBE" }
            views.setTextViewText(R.id.widget_title, "$devName Cockpit")
            views.setTextViewText(
                R.id.widget_battery_pct,
                if (isCharging) "⚡ $batteryPct%" else "$batteryPct%"
            )
            views.setProgressBar(R.id.widget_battery_bar, 100, batteryPct, false)

            if (isCharging) {
                val to80Sec = charging.remainingSecondsTo80
                val timeTo80Str = when {
                    batteryPct >= 80 -> "To 80%: Done (≥80%)"
                    to80Sec != null && to80Sec > 0 -> {
                        val mins = (to80Sec / 60).coerceAtLeast(1)
                        if (mins >= 60) "To 80%: ${mins / 60}h ${mins % 60}m" else "To 80%: ${mins}m"
                    }
                    else -> "To 80%: Estimating..."
                }

                val toFullSec = charging.remainingSecondsTo100
                val timeToFullStr = when {
                    batteryPct >= 100 -> "Full: 100% Charged"
                    toFullSec != null && toFullSec > 0 -> {
                        val mins = (toFullSec / 60).coerceAtLeast(1)
                        if (mins >= 60) "To Full: ${mins / 60}h ${mins % 60}m" else "To Full: ${mins}m"
                    }
                    else -> "To Full: Estimating..."
                }

                views.setTextViewText(R.id.widget_cluster_range, timeTo80Str)
                views.setTextColor(R.id.widget_cluster_range, Color.parseColor("#00E676")) // Neon Mint
                views.setTextViewText(R.id.widget_true_range, timeToFullStr)
                views.setTextColor(R.id.widget_true_range, Color.parseColor("#00E5FF")) // Electric Cyan
            } else {
                views.setTextViewText(
                    R.id.widget_cluster_range,
                    String.format(Locale.US, "DTE: %.0f km", clusterRangeKm)
                )
                views.setTextColor(R.id.widget_cluster_range, Color.parseColor("#F1F5F9"))
                views.setTextViewText(
                    R.id.widget_true_range,
                    String.format(Locale.US, "True: %.0f km (Eco) • %.0f km (Pwr)", ecoTrue, pwrTrue)
                )
                views.setTextColor(R.id.widget_true_range, Color.parseColor("#94A3B8"))
            }

            views.setTextViewText(R.id.widget_location, "📍 $locality")

            val timeStr = when {
                charging.isDisconnectedPredicted -> "⚠️ Predicted • Needs Calibration"
                isCharging -> "⚡ Charging Active"
                isConnected -> "Live Telemetry"
                charging.needsCalibration -> "⚠️ Needs Calibration"
                else -> {
                    val ts = lastSeen?.timestampMs ?: System.currentTimeMillis()
                    "Last seen " + SimpleDateFormat("hh:mm a", Locale.US).format(Date(ts))
                }
            }
            views.setTextViewText(R.id.widget_last_seen, timeStr)

            // Click Intent opens MainActivity
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
