package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.ble.IqubeSharedStorageManager
import com.example.model.ClickAction
import com.example.model.WidgetCustomTemplate
import com.example.model.WidgetGridSize
import java.util.Locale

/**
 * Primary (4×2 Full Cockpit) App Widget Provider & Base Renderer for all TVS iQube Widget Slots.
 * Supports live responsive resizing (1×1 up to 4×3), custom Studio canvas elements,
 * custom border roundness, and Canva graphic backgrounds.
 */
open class IqubeAppWidgetProvider : AppWidgetProvider() {

    protected open val slotIndex: Int = 0

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId, slotIndex)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateSingleWidget(context, appWidgetManager, appWidgetId, slotIndex, newOptions)
    }

    companion object {
        private fun getAllProviderClasses(): List<Pair<Int, Class<out IqubeAppWidgetProvider>>> {
            return listOf(
                0 to IqubeAppWidgetProvider::class.java,
                1 to IqubeCompact2x1WidgetProvider::class.java,
                2 to IqubeMini1x1WidgetProvider::class.java,
                3 to IqubeCustomSlot3WidgetProvider::class.java,
                4 to IqubeCustomSlot4WidgetProvider::class.java
            )
        }

        fun updateAllWidgets(context: Context) {
            runCatching {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                for ((slotIdx, cls) in getAllProviderClasses()) {
                    val component = ComponentName(context, cls)
                    val ids = appWidgetManager.getAppWidgetIds(component)
                    for (widgetId in ids) {
                        updateSingleWidget(context, appWidgetManager, widgetId, slotIdx)
                    }
                }
            }
            com.example.tile.IqubeQuickSettingsTileService.requestTileUpdate(context)
        }

        /**
         * Enables the corresponding Android AppWidget provider in the launcher's "Add Widgets" menu
         * and requests pinning if supported by the launcher.
         */
        fun activateAndPinWidgetTemplate(
            context: Context,
            template: WidgetCustomTemplate,
            requestSystemPinDialog: Boolean = false
        ): Boolean {
            val slot = when {
                template.widgetSlotIndex in 1..4 -> template.widgetSlotIndex
                template.gridSize == WidgetGridSize.SIZE_1X1 -> 2
                template.gridSize == WidgetGridSize.SIZE_2X1 -> 1
                else -> 0
            }
            val updatedTemplate = template.copy(widgetSlotIndex = slot)
            val storage = IqubeSharedStorageManager.getInstance(context)
            storage.upsertTemplate(updatedTemplate)

            val targetClass = getAllProviderClasses().firstOrNull { it.first == slot }?.second
                ?: IqubeAppWidgetProvider::class.java
            val componentName = ComponentName(context, targetClass)

            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            updateAllWidgets(context)

            if (requestSystemPinDialog && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return runCatching {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    if (appWidgetManager.isRequestPinAppWidgetSupported) {
                        appWidgetManager.requestPinAppWidget(componentName, null, null)
                    } else false
                }.getOrDefault(false)
            }
            return true
        }

        internal fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            slotIndex: Int,
            optionsOverride: Bundle? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_iqube_cockpit)
            val storage = IqubeSharedStorageManager.getInstance(context)
            val template = storage.getTemplateForAppWidget(appWidgetId, slotIndex)
            val snapshot = CustomSurfaceCanvasRenderer.captureLiveSnapshot(context)

            // Resolve live launcher dimensions when user resizes the widget
            val options = optionsOverride ?: runCatching { appWidgetManager.getAppWidgetOptions(appWidgetId) }.getOrNull()
            val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 40 }
                ?: template.gridSize.minWidthDp
            val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)?.takeIf { it > 40 }
                ?: template.gridSize.minHeightDp

            // Render custom Studio canvas into a high-DPI Bitmap
            val renderedBitmap = CustomSurfaceCanvasRenderer.renderTemplateToBitmap(
                context = context,
                template = template,
                targetWidthDp = minW,
                targetHeightDp = minH,
                snapshot = snapshot
            )
            views.setImageViewBitmap(R.id.widget_custom_canvas_img, renderedBitmap)

            // Also populate fallback views for accessibility & unit tests
            views.setTextViewText(R.id.widget_title, snapshot.vehicleName)
            views.setTextViewText(R.id.widget_status_badge, snapshot.statusBadgeText)
            runCatching {
                views.setTextColor(R.id.widget_status_badge, Color.parseColor(snapshot.statusBadgeColorHex))
            }
            views.setTextViewText(
                R.id.widget_battery_pct,
                if (snapshot.isCharging) "⚡ ${snapshot.batteryPct}%" else "${snapshot.batteryPct}%"
            )
            views.setProgressBar(R.id.widget_battery_bar, 100, snapshot.batteryPct, false)

            if (snapshot.isCharging) {
                views.setTextViewText(R.id.widget_cluster_range, "To 80%: ${snapshot.timeTo80Str}")
                views.setTextViewText(R.id.widget_true_range, "To Full: ${snapshot.timeToFullStr}")
            } else {
                views.setTextViewText(
                    R.id.widget_cluster_range,
                    String.format(Locale.US, "DTE: %.0f km", snapshot.clusterRangeKm)
                )
                views.setTextViewText(
                    R.id.widget_true_range,
                    String.format(Locale.US, "True: %.0f km (Eco) • %.0f km (Pwr)", snapshot.trueEcoKm, snapshot.truePowerKm)
                )
            }
            views.setTextViewText(R.id.widget_location, "📍 ${snapshot.localityName}")
            views.setTextViewText(R.id.widget_last_seen, snapshot.lastSeenFormatted)

            // Click Intent configured by template
            val pendingIntent = buildClickPendingIntent(context, template.clickAction, appWidgetId)
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun buildClickPendingIntent(
            context: Context,
            clickAction: ClickAction,
            requestCodeSeed: Int = 0
        ): PendingIntent {
            return when (clickAction) {
                ClickAction.TRIGGER_BLE_CONNECT -> {
                    val connectIntent = Intent(context, com.example.ble.IqubeBleForegroundService::class.java).apply {
                        action = com.example.ble.IqubeBleForegroundService.ACTION_TRIGGER_CONNECT
                    }
                    PendingIntent.getService(
                        context,
                        100 + requestCodeSeed,
                        connectIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                ClickAction.OPEN_STUDIO_SANDBOX -> {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("NAV_DESTINATION", "studio_sandbox")
                    }
                    PendingIntent.getActivity(
                        context,
                        200 + requestCodeSeed,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                ClickAction.OPEN_SAVED_TRIPS -> {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("NAV_DESTINATION", "saved_trips")
                    }
                    PendingIntent.getActivity(
                        context,
                        300 + requestCodeSeed,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                else -> {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    PendingIntent.getActivity(
                        context,
                        requestCodeSeed,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            }
        }
    }
}

/** Slot 1: Compact 2×1 Pill Widget in Launcher "Add Widgets" */
class IqubeCompact2x1WidgetProvider : IqubeAppWidgetProvider() {
    override val slotIndex: Int = 1
}

/** Slot 2: Mini 1×1 Cube Widget in Launcher "Add Widgets" */
class IqubeMini1x1WidgetProvider : IqubeAppWidgetProvider() {
    override val slotIndex: Int = 2
}

/** Slot 3: Custom Created Studio Widget #1 in Launcher "Add Widgets" */
class IqubeCustomSlot3WidgetProvider : IqubeAppWidgetProvider() {
    override val slotIndex: Int = 3
}

/** Slot 4: Custom Created Studio Widget #2 in Launcher "Add Widgets" */
class IqubeCustomSlot4WidgetProvider : IqubeAppWidgetProvider() {
    override val slotIndex: Int = 4
}
