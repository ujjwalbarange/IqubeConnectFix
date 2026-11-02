package com.example.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.ble.IqubeBleManager
import com.example.model.BleConnectionPhase
import com.example.model.ElementType
import com.example.model.TemplateElement
import com.example.model.TileCustomConfig
import com.example.model.TileMetricType
import com.example.model.WidgetCustomTemplate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LiveSurfaceTelemetrySnapshot(
    val vehicleName: String,
    val batteryPct: Int,
    val clusterRangeKm: Double,
    val trueEcoKm: Double,
    val truePowerKm: Double,
    val speedKmh: Int,
    val odometerKm: Double,
    val tripAKm: Double,
    val tripBKm: Double,
    val localityName: String,
    val lastSeenFormatted: String,
    val isConnected: Boolean,
    val isConnecting: Boolean,
    val isCharging: Boolean,
    val isDisconnectedPredicted: Boolean,
    val needsCalibration: Boolean,
    val timeTo80Str: String,
    val timeToFullStr: String,
    val statusBadgeText: String,
    val statusBadgeColorHex: String
)

object CustomSurfaceCanvasRenderer {

    fun captureLiveSnapshot(context: Context): LiveSurfaceTelemetrySnapshot {
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
        val parked = bleManager.lastParkedLocation.value
        val trueRange = bleManager.trueRangeState.value
        val charging = bleManager.chargingMonitorState.value
        val isCharging = (isConnected && tele.isCharging) || charging.isCharging

        val batteryPct = (
            tele.batteryPercent
                ?: charging.currentBatteryPct
                ?: parked?.batteryPercentAtPark
                ?: lastSeen?.batteryPct
                ?: 93
            ).coerceIn(0, 100)

        val clusterRangeKm = (
            tele.clusterRangeKm
                ?: charging.clusterRangeKm
                ?: parked?.rangeKmAtPark
                ?: lastSeen?.clusterRangeKm
                ?: 98.0
            ).coerceAtLeast(0.0)

        val ecoTrue = trueRange.trueEcoRangeKm ?: (batteryPct * trueRange.ecoKmPerPct)
        val pwrTrue = trueRange.truePowerRangeKm ?: (batteryPct * trueRange.powerKmPerPct)

        val speedKmh = tele.speedKmh ?: 0
        val odometerKm = tele.odometerKm ?: parked?.odometerKmAtPark ?: lastSeen?.odometerKm ?: 35925.2
        val tripAKm = tele.tripAKm ?: lastSeen?.tripAKm ?: 0.3
        val tripBKm = tele.tripBKm ?: lastSeen?.tripBKm ?: 1.0

        val locality = bleManager.detectedLocalityName.value.ifBlank {
            parked?.locationName?.takeIf { it.isNotBlank() }
                ?: lastSeen?.locationName?.takeIf { it.isNotBlank() }
                ?: "Dhantoli"
        }

        val to80Sec = charging.remainingSecondsTo80
        val timeTo80Str = when {
            batteryPct >= 80 -> "Done (≥80%)"
            to80Sec != null && to80Sec > 0 -> {
                val mins = (to80Sec / 60).coerceAtLeast(1)
                if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
            }
            else -> "42m"
        }

        val toFullSec = charging.remainingSecondsTo100
        val timeToFullStr = when {
            batteryPct >= 100 -> "100% Full"
            toFullSec != null && toFullSec > 0 -> {
                val mins = (toFullSec / 60).coerceAtLeast(1)
                if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
            }
            else -> "1h 15m"
        }

        val (statusText, statusHex) = when {
            charging.isDisconnectedPredicted -> "⚡ Pred • Calibrate" to "#FFB300"
            isCharging -> "⚡ Charging" to "#00E5FF"
            isConnected -> "● Connected" to "#00E676"
            isConnecting -> "⟳ Connecting" to "#FFB300"
            charging.needsCalibration -> "⚠️ Needs Cal" to "#FFB300"
            else -> "○ Disconnected" to "#94A3B8"
        }

        val lastSeenStr = when {
            charging.isDisconnectedPredicted -> "Predicted • Calibrate"
            isCharging -> "To 80%: $timeTo80Str • Full: $timeToFullStr"
            isConnected -> if (speedKmh > 0) "Riding $speedKmh km/h" else "Live Cockpit"
            else -> {
                val ts = parked?.timestampMs ?: lastSeen?.timestampMs ?: System.currentTimeMillis()
                "Last seen " + SimpleDateFormat("hh:mm a", Locale.US).format(Date(ts))
            }
        }

        val devName = tele.vehicleIdString?.takeIf { it.isNotBlank() }
            ?: bleManager.connectedDeviceName.value.takeIf { it.isNotBlank() }
            ?: lastSeen?.vehicleId?.takeIf { it.isNotBlank() }
            ?: "TVSiQUBE"

        return LiveSurfaceTelemetrySnapshot(
            vehicleName = "$devName Cockpit",
            batteryPct = batteryPct,
            clusterRangeKm = clusterRangeKm,
            trueEcoKm = ecoTrue,
            truePowerKm = pwrTrue,
            speedKmh = speedKmh,
            odometerKm = odometerKm,
            tripAKm = tripAKm,
            tripBKm = tripBKm,
            localityName = locality,
            lastSeenFormatted = lastSeenStr,
            isConnected = isConnected,
            isConnecting = isConnecting,
            isCharging = isCharging,
            isDisconnectedPredicted = charging.isDisconnectedPredicted,
            needsCalibration = charging.needsCalibration,
            timeTo80Str = timeTo80Str,
            timeToFullStr = timeToFullStr,
            statusBadgeText = statusText,
            statusBadgeColorHex = statusHex
        )
    }

    /**
     * Renders any [WidgetCustomTemplate] (Home Widget or Widget-Style Notification)
     * into a crisp anti-aliased [Bitmap] respecting border roundness, border stroke,
     * Canva background art, custom element positions, font sizes, and responsive bounds.
     */
    fun renderTemplateToBitmap(
        context: Context,
        template: WidgetCustomTemplate,
        targetWidthDp: Int,
        targetHeightDp: Int,
        snapshot: LiveSurfaceTelemetrySnapshot = captureLiveSnapshot(context)
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceIn(1.5f, 3.0f)
        val widthPx = (targetWidthDp * density).roundToInt().coerceIn(160, 1200)
        val heightPx = (targetHeightDp * density).roundToInt().coerceIn(110, 900)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cornerRadiusPx = (template.cornerRadiusDp * density).coerceIn(0f, min(widthPx, heightPx) / 2f)
        val rectF = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())

        // Clip canvas to rounded border
        val clipPath = Path().apply {
            addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        // 1. Draw Background Gradient
        val bgStartColor = parseColorSafe(template.backgroundColorHex, Color.parseColor("#0B0F19"))
        val bgEndColor = parseColorSafe(template.backgroundGradientEndHex, Color.parseColor("#131D31"))
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, heightPx.toFloat(),
                bgStartColor, bgEndColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rectF, bgPaint)

        // 2. Draw Imported Canva / Custom Background Image (if present)
        val imagePath = template.backgroundImagePath
        if (!imagePath.isNullOrBlank()) {
            runCatching {
                val imgFile = File(imagePath)
                if (imgFile.exists()) {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val rawBmp = BitmapFactory.decodeFile(imgFile.absolutePath, opts)
                    if (rawBmp != null) {
                        val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            alpha = (template.backgroundImageOpacity.coerceIn(0.05f, 1.0f) * 255).roundToInt()
                        }
                        val matrix = Matrix()
                        val scale = max(
                            widthPx.toFloat() / rawBmp.width.toFloat(),
                            heightPx.toFloat() / rawBmp.height.toFloat()
                        )
                        val dx = (widthPx - rawBmp.width * scale) * 0.5f
                        val dy = (heightPx - rawBmp.height * scale) * 0.5f
                        matrix.setScale(scale, scale)
                        matrix.postTranslate(dx, dy)
                        canvas.drawBitmap(rawBmp, matrix, imgPaint)
                    }
                }
            }
        }

        // Responsive scale factor when widget is resized smaller/larger than nominal height
        val nominalHeightDp = template.gridSize.minHeightDp.coerceAtLeast(70)
        val scaleFactor = (targetHeightDp.toFloat() / nominalHeightDp.toFloat()).coerceIn(0.72f, 1.35f)
        val padPx = (template.paddingDp * density * scaleFactor).coerceIn(8f * density, 28f * density)

        // 3. Draw Each Visible Template Element
        for (element in template.elements) {
            if (!element.visible) continue
            drawElementOnCanvas(
                canvas = canvas,
                element = element,
                template = template,
                snapshot = snapshot,
                widthPx = widthPx.toFloat(),
                heightPx = heightPx.toFloat(),
                density = density,
                scaleFactor = scaleFactor,
                padPx = padPx
            )
        }

        canvas.restore()

        // 4. Draw Rounded Border Stroke
        if (template.borderWidthDp > 0.1f) {
            val strokePx = (template.borderWidthDp * density).coerceIn(1f, 14f)
            val halfStroke = strokePx / 2f
            val borderRect = RectF(halfStroke, halfStroke, widthPx - halfStroke, heightPx - halfStroke)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                color = parseColorSafe(template.borderColorHex, Color.parseColor("#283548"))
            }
            val innerRadius = (cornerRadiusPx - halfStroke).coerceAtLeast(0f)
            canvas.drawRoundRect(borderRect, innerRadius, innerRadius, borderPaint)
        }

        return bitmap
    }

    private fun drawElementOnCanvas(
        canvas: Canvas,
        element: TemplateElement,
        template: WidgetCustomTemplate,
        snapshot: LiveSurfaceTelemetrySnapshot,
        widthPx: Float,
        heightPx: Float,
        density: Float,
        scaleFactor: Float,
        padPx: Float
    ) {
        val rawX = (element.xPercent / 100f) * widthPx
        val rawY = (element.yPercent / 100f) * heightPx
        val elemColor = parseColorSafe(element.colorHex, Color.WHITE)
        val accentColor = parseColorSafe(template.accentColorHex, Color.parseColor("#00E5FF"))

        when (element.type) {
            ElementType.BATTERY_BAR, ElementType.DECORATIVE_PILL -> {
                val barWidth = ((element.widthPercent.coerceIn(15f, 94f) / 100f) * widthPx)
                    .coerceAtMost(widthPx - rawX - padPx * 0.5f)
                    .coerceAtLeast(24f * density)
                val barHeight = (element.fontSizeSp.coerceIn(4, 18) * density * scaleFactor).coerceAtLeast(4f * density)
                val topY = rawY.coerceIn(padPx * 0.4f, heightPx - barHeight - padPx * 0.4f)
                val leftX = rawX.coerceIn(padPx * 0.5f, (widthPx - barWidth - padPx * 0.5f).coerceAtLeast(padPx * 0.5f))

                val trackRect = RectF(leftX, topY, leftX + barWidth, topY + barHeight)
                val radius = barHeight / 2f

                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb(85, 100, 116, 139)
                }
                canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

                val progressFraction = if (element.type == ElementType.DECORATIVE_PILL) {
                    1f
                } else {
                    (snapshot.batteryPct / 100f).coerceIn(0.04f, 1f)
                }
                val fillRect = RectF(leftX, topY, leftX + barWidth * progressFraction, topY + barHeight)
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = if (element.type == ElementType.BATTERY_BAR && snapshot.batteryPct <= 20) {
                        Color.parseColor("#FFB300")
                    } else {
                        elemColor
                    }
                }
                canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
            }

            ElementType.STATUS_BADGE -> {
                val text = snapshot.statusBadgeText
                val badgeColor = parseColorSafe(snapshot.statusBadgeColorHex, elemColor)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    color = badgeColor
                    textSize = element.fontSizeSp * density * scaleFactor
                    typeface = Typeface.create(Typeface.DEFAULT, if (element.isBold) Typeface.BOLD else Typeface.NORMAL)
                }
                val maxAllowedWidth = (widthPx - padPx * 1.2f).coerceAtLeast(40f)
                while (textPaint.measureText(text) > maxAllowedWidth && textPaint.textSize > 8f * density) {
                    textPaint.textSize -= 1f * density
                }
                val textW = textPaint.measureText(text)
                val fm = textPaint.fontMetrics
                val textH = fm.descent - fm.ascent

                val chipPadH = 6f * density
                val chipPadV = 3f * density
                val totalW = textW + chipPadH * 2f
                val totalH = textH + chipPadV * 2f

                val clampedX = rawX.coerceIn(padPx * 0.5f, (widthPx - totalW - padPx * 0.5f).coerceAtLeast(padPx * 0.5f))
                val clampedY = rawY.coerceIn(padPx * 0.4f, (heightPx - totalH - padPx * 0.4f).coerceAtLeast(padPx * 0.4f))

                val pillRect = RectF(clampedX, clampedY, clampedX + totalW, clampedY + totalH)
                val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb(42, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor))
                }
                canvas.drawRoundRect(pillRect, totalH / 2f, totalH / 2f, pillBgPaint)

                val baselineY = clampedY + chipPadV - fm.ascent
                canvas.drawText(text, clampedX + chipPadH, baselineY, textPaint)
            }

            else -> {
                val text = resolveElementText(element, snapshot)
                if (text.isBlank()) return

                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    color = if (element.type == ElementType.BATTERY_PERCENT && snapshot.isCharging) {
                        accentColor
                    } else {
                        elemColor
                    }
                    textSize = element.fontSizeSp * density * scaleFactor
                    typeface = Typeface.create(Typeface.DEFAULT, if (element.isBold) Typeface.BOLD else Typeface.NORMAL)
                }

                // Auto-fit text so it never clips outside the card width
                val maxAllowedWidth = (widthPx - padPx * 1.2f).coerceAtLeast(40f)
                while (paint.measureText(text) > maxAllowedWidth && paint.textSize > 8.5f * density) {
                    paint.textSize -= 1f * density
                }

                val textW = paint.measureText(text)
                val fm = paint.fontMetrics
                val textH = fm.descent - fm.ascent

                val clampedX = rawX.coerceIn(padPx * 0.5f, (widthPx - textW - padPx * 0.5f).coerceAtLeast(padPx * 0.5f))
                val clampedY = rawY.coerceIn(padPx * 0.3f, (heightPx - textH - padPx * 0.3f).coerceAtLeast(padPx * 0.3f))
                val baselineY = clampedY - fm.ascent

                canvas.drawText(text, clampedX, baselineY, paint)
            }
        }
    }

    fun resolveElementText(element: TemplateElement, snapshot: LiveSurfaceTelemetrySnapshot): String {
        return when (element.type) {
            ElementType.BATTERY_PERCENT -> if (snapshot.isCharging) "⚡${snapshot.batteryPct}%" else "${snapshot.batteryPct}%"
            ElementType.RANGE_KM -> if (snapshot.isCharging) {
                "To 80%: ${snapshot.timeTo80Str}"
            } else {
                String.format(Locale.US, "DTE: %.0f km", snapshot.clusterRangeKm)
            }
            ElementType.TRUE_RANGE_ECO_POWER -> if (snapshot.isCharging) {
                "Full: ${snapshot.timeToFullStr}"
            } else {
                String.format(Locale.US, "True: %.0f km (Eco) • %.0f km (Pwr)", snapshot.trueEcoKm, snapshot.truePowerKm)
            }
            ElementType.VEHICLE_TITLE -> element.customText.takeIf { it.isNotBlank() } ?: snapshot.vehicleName
            ElementType.CHARGING_ETA_OR_TIME -> if (snapshot.isCharging) {
                "⚡ 80%: ${snapshot.timeTo80Str} • Full: ${snapshot.timeToFullStr}"
            } else {
                "True Eco: ${snapshot.trueEcoKm.roundToInt()} km"
            }
            ElementType.LAST_PARKED_LOCATION -> "📍 ${snapshot.localityName}"
            ElementType.LAST_SEEN_TIMESTAMP -> snapshot.lastSeenFormatted
            ElementType.LIVE_SPEED_KMH -> "${snapshot.speedKmh} km/h"
            ElementType.ODOMETER_KM -> String.format(Locale.US, "ODO %.1f km", snapshot.odometerKm)
            ElementType.TRIP_A_B_DISTANCE -> String.format(Locale.US, "Trip A: %.1f • B: %.1f km", snapshot.tripAKm, snapshot.tripBKm)
            ElementType.CUSTOM_TEXT -> element.customText.ifBlank { "iQube Smart Mobility" }
            ElementType.STATUS_BADGE -> snapshot.statusBadgeText
            ElementType.BATTERY_BAR, ElementType.DECORATIVE_PILL -> ""
        }
    }

    fun resolveTileMetricText(
        metric: TileMetricType,
        snapshot: LiveSurfaceTelemetrySnapshot,
        customText: String = ""
    ): String {
        return when (metric) {
            TileMetricType.BATTERY_PCT -> if (snapshot.isCharging) "⚡${snapshot.batteryPct}%" else "${snapshot.batteryPct}%"
            TileMetricType.DTE_RANGE_KM -> "${snapshot.clusterRangeKm.roundToInt()}km"
            TileMetricType.TRUE_ECO_KM -> "${snapshot.trueEcoKm.roundToInt()}km"
            TileMetricType.TRUE_POWER_KM -> "${snapshot.truePowerKm.roundToInt()}km"
            TileMetricType.SPEED_KMH -> "${snapshot.speedKmh}km/h"
            TileMetricType.ODOMETER_KM -> String.format(Locale.US, "%.1fk", snapshot.odometerKm / 1000.0)
            TileMetricType.CHARGING_TO_80 -> snapshot.timeTo80Str
            TileMetricType.CHARGING_TO_100 -> snapshot.timeToFullStr
            TileMetricType.LOCATION_NAME -> snapshot.localityName.take(8)
            TileMetricType.CUSTOM_TEXT -> customText.ifBlank { "iQube" }
            TileMetricType.NONE_SINGLE_LINE -> ""
        }
    }

    /**
     * Renders the Control Center Quick Settings Tile icon bitmap using the user's [TileCustomConfig]
     * (customizable top parameter, bottom parameter, font sizes, bold toggle, and optional border box).
     */
    fun renderControlCenterTileBitmap(
        config: TileCustomConfig,
        snapshot: LiveSurfaceTelemetrySnapshot
    ): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (config.showBorderBox) {
            val strokeW = 5f
            val half = strokeW / 2f
            val rect = RectF(half, half, size - half, size - half)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                color = Color.WHITE
            }
            val r = config.borderCornerRadiusPx.coerceIn(0f, 60f)
            canvas.drawRoundRect(rect, r, r, borderPaint)
        }

        val topText = resolveTileMetricText(config.topMetric, snapshot, config.customTopText)
        val bottomText = resolveTileMetricText(config.bottomMetric, snapshot, config.customBottomText)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (config.isBold) Typeface.BOLD else Typeface.NORMAL
            )
        }

        val maxAllowedWidth = if (config.showBorderBox) size * 0.84f else size * 0.94f

        if (config.bottomMetric == TileMetricType.NONE_SINGLE_LINE || bottomText.isBlank()) {
            var topSize = config.topFontSizePx.coerceIn(28f, 72f)
            paint.textSize = topSize
            while (paint.measureText(topText) > maxAllowedWidth && topSize > 22f) {
                topSize -= 2f
                paint.textSize = topSize
            }
            val fm = paint.fontMetrics
            val centerY = size * 0.50f
            val baselineY = centerY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(topText, size / 2f, baselineY, paint)
        } else {
            var topSize = config.topFontSizePx.coerceIn(24f, 68f)
            paint.textSize = topSize
            while (paint.measureText(topText) > maxAllowedWidth && topSize > 22f) {
                topSize -= 2f
                paint.textSize = topSize
            }
            val topMetrics = paint.fontMetrics
            val topCenterY = size * 0.33f
            val topBaselineY = topCenterY - (topMetrics.ascent + topMetrics.descent) / 2f
            canvas.drawText(topText, size / 2f, topBaselineY, paint)

            var bottomSize = config.bottomFontSizePx.coerceIn(18f, 54f)
            paint.textSize = bottomSize
            while (paint.measureText(bottomText) > maxAllowedWidth && bottomSize > 18f) {
                bottomSize -= 2f
                paint.textSize = bottomSize
            }
            val bottomMetrics = paint.fontMetrics
            val bottomCenterY = size * 0.74f
            val bottomBaselineY = bottomCenterY - (bottomMetrics.ascent + bottomMetrics.descent) / 2f
            canvas.drawText(bottomText, size / 2f, bottomBaselineY, paint)
        }

        return bitmap
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
    }
}
