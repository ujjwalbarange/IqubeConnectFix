package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TargetSurface(val title: String, val subtitle: String) {
    APP_WIDGET("Home Widgets", "Create & edit resizable 1×1 to 4×3 home widgets"),
    STATUS_NOTIFICATION("Notification Style", "Widget-style rich telemetry notification card"),
    CONTROL_CENTER_TILE("Control Center Tile", "Customize Quick Settings tile metrics & font size")
}

enum class WidgetGridSize(
    val shortLabel: String,
    val title: String,
    val cellsW: Int,
    val cellsH: Int,
    val aspectRatio: Float,
    val minWidthDp: Int,
    val minHeightDp: Int
) {
    SIZE_1X1("1×1", "Mini Cube (1×1)", 1, 1, 1.0f, 70, 70),
    SIZE_2X1("2×1", "Compact Pill (2×1)", 2, 1, 2.1f, 140, 70),
    SIZE_2X2("2×2", "Square Card (2×2)", 2, 2, 1.05f, 140, 140),
    SIZE_3X1("3×1", "Horizontal Strip (3×1)", 3, 1, 2.8f, 210, 70),
    SIZE_3X2("3×2", "Standard Card (3×2)", 3, 2, 1.55f, 210, 140),
    SIZE_4X1("4×1", "Wide Ribbon (4×1)", 4, 1, 3.4f, 260, 75),
    SIZE_4X2("4×2", "Full Cockpit (4×2)", 4, 2, 1.95f, 260, 135),
    SIZE_4X3("4×3", "Telemetry Board (4×3)", 4, 3, 1.35f, 260, 200)
}

enum class WidgetThemePreset(
    val title: String,
    val bgHex: String,
    val bgGradientEnd: String,
    val accentHex: String,
    val textHex: String,
    val borderHex: String = "#283548"
) {
    CYBER_NEON("Cyber Neon", "#0B0F19", "#131D31", "#00E676", "#F8FAFC", "#1E3A3A"),
    ELECTRIC_CYAN("Electric Cyan", "#070D18", "#0E1A2D", "#00E5FF", "#F1F5F9", "#163B50"),
    SUNSET_CORAL("Sunset Coral", "#14080E", "#26101B", "#FF5252", "#FFF1F2", "#4A1D2F"),
    AMBER_VOLT("Amber Volt", "#141006", "#261E0A", "#FFB300", "#FFFBEB", "#4A3B14"),
    STEALTH_OLED("Stealth OLED", "#000000", "#080B10", "#FFFFFF", "#E2E8F0", "#262626"),
    GLASS_SLATE("Glass Slate", "#1E293B", "#0F172A", "#38BDF8", "#F8FAFC", "#334155"),
    CANVA_CUSTOM("Canva / Custom Art", "#0F172A", "#1E293B", "#00E5FF", "#FFFFFF", "#00E5FF")
}

enum class ClickAction(val title: String, val description: String) {
    OPEN_COCKPIT("Open Cockpit", "Launches the live telemetry cockpit dashboard"),
    TRIGGER_BLE_CONNECT("Trigger BLE Connect", "Instantly starts scanning & auto-connecting to scooter"),
    TOGGLE_RIDE_MODE("Toggle Ride Mode", "Switches between Eco and Power true range modes"),
    OPEN_SAVED_TRIPS("Open Saved Trips", "Opens saved trips & charging history"),
    OPEN_STUDIO_SANDBOX("Open Customization Studio", "Opens the template editing sandbox")
}

enum class ElementType(val defaultLabel: String, val defaultFontSizeSp: Int = 13) {
    BATTERY_PERCENT("Battery SoC %", 26),
    BATTERY_BAR("Battery Progress Bar", 8),
    RANGE_KM("Cluster DTE Range", 15),
    TRUE_RANGE_ECO_POWER("Learned True Range (Eco/Pwr)", 11),
    STATUS_BADGE("Connection & Charging Badge", 11),
    VEHICLE_TITLE("Vehicle Name / Header", 13),
    CHARGING_ETA_OR_TIME("Charging Time to 80% / Full", 11),
    LAST_PARKED_LOCATION("Parked Location & Pin", 11),
    LAST_SEEN_TIMESTAMP("Last Seen Timestamp", 10),
    LIVE_SPEED_KMH("Live Speed (km/h)", 18),
    ODOMETER_KM("Odometer Reading (km)", 12),
    TRIP_A_B_DISTANCE("Trip A & Trip B (km)", 11),
    CUSTOM_TEXT("Custom Accent Tagline", 12),
    DECORATIVE_PILL("Accent Divider / Bar", 6)
}

enum class TileMetricType(val title: String, val samplePreview: String) {
    BATTERY_PCT("Battery %", "93%"),
    DTE_RANGE_KM("DTE Range (km)", "98km"),
    TRUE_ECO_KM("True Eco Range (km)", "93km"),
    TRUE_POWER_KM("True Power Range (km)", "70km"),
    SPEED_KMH("Live Speed (km/h)", "42km/h"),
    ODOMETER_KM("Odometer (km)", "35.9k"),
    CHARGING_TO_80("Charging Time to 80%", "42m"),
    CHARGING_TO_100("Charging Time to Full", "1h15m"),
    LOCATION_NAME("Parked Locality", "Dhantoli"),
    CUSTOM_TEXT("Custom Text", "iQube"),
    NONE_SINGLE_LINE("None (Single Line Large)", "")
}

data class TileCustomConfig(
    val topMetric: TileMetricType = TileMetricType.BATTERY_PCT,
    val bottomMetric: TileMetricType = TileMetricType.DTE_RANGE_KM,
    val labelMetric: TileMetricType = TileMetricType.BATTERY_PCT,
    val subtitleMetric: TileMetricType = TileMetricType.TRUE_ECO_KM,
    val topFontSizePx: Float = 52f,
    val bottomFontSizePx: Float = 40f,
    val isBold: Boolean = true,
    val showBorderBox: Boolean = false,
    val borderCornerRadiusPx: Float = 26f,
    val customTopText: String = "iQube",
    val customBottomText: String = "98km"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("topMetric", topMetric.name)
            put("bottomMetric", bottomMetric.name)
            put("labelMetric", labelMetric.name)
            put("subtitleMetric", subtitleMetric.name)
            put("topFontSizePx", topFontSizePx.toDouble())
            put("bottomFontSizePx", bottomFontSizePx.toDouble())
            put("isBold", isBold)
            put("showBorderBox", showBorderBox)
            put("borderCornerRadiusPx", borderCornerRadiusPx.toDouble())
            put("customTopText", customTopText)
            put("customBottomText", customBottomText)
        }
    }

    companion object {
        fun fromJson(json: JSONObject?): TileCustomConfig {
            if (json == null) return TileCustomConfig()
            val top = runCatching { TileMetricType.valueOf(json.optString("topMetric", TileMetricType.BATTERY_PCT.name)) }
                .getOrDefault(TileMetricType.BATTERY_PCT)
            val bottom = runCatching { TileMetricType.valueOf(json.optString("bottomMetric", TileMetricType.DTE_RANGE_KM.name)) }
                .getOrDefault(TileMetricType.DTE_RANGE_KM)
            val label = runCatching { TileMetricType.valueOf(json.optString("labelMetric", TileMetricType.BATTERY_PCT.name)) }
                .getOrDefault(TileMetricType.BATTERY_PCT)
            val sub = runCatching { TileMetricType.valueOf(json.optString("subtitleMetric", TileMetricType.TRUE_ECO_KM.name)) }
                .getOrDefault(TileMetricType.TRUE_ECO_KM)

            return TileCustomConfig(
                topMetric = top,
                bottomMetric = bottom,
                labelMetric = label,
                subtitleMetric = sub,
                topFontSizePx = json.optDouble("topFontSizePx", 52.0).toFloat().coerceIn(22f, 72f),
                bottomFontSizePx = json.optDouble("bottomFontSizePx", 40.0).toFloat().coerceIn(18f, 58f),
                isBold = json.optBoolean("isBold", true),
                showBorderBox = json.optBoolean("showBorderBox", false),
                borderCornerRadiusPx = json.optDouble("borderCornerRadiusPx", 26.0).toFloat().coerceIn(0f, 64f),
                customTopText = json.optString("customTopText", "iQube"),
                customBottomText = json.optString("customBottomText", "98km")
            )
        }
    }
}

data class TemplateElement(
    val id: String = UUID.randomUUID().toString(),
    val type: ElementType,
    val label: String = type.defaultLabel,
    val visible: Boolean = true,
    val xPercent: Float = 4f, // 0f..95f
    val yPercent: Float = 8f, // 0f..92f
    val widthPercent: Float = 42f, // Used for progress bars & dividers
    val fontSizeSp: Int = type.defaultFontSizeSp,
    val colorHex: String = "#FFFFFF",
    val isBold: Boolean = true,
    val customText: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("label", label)
            put("visible", visible)
            put("xPercent", xPercent.toDouble())
            put("yPercent", yPercent.toDouble())
            put("widthPercent", widthPercent.toDouble())
            put("fontSizeSp", fontSizeSp)
            put("colorHex", colorHex)
            put("isBold", isBold)
            put("customText", customText)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TemplateElement {
            val typeStr = json.optString("type", ElementType.BATTERY_PERCENT.name)
            val type = runCatching { ElementType.valueOf(typeStr) }.getOrDefault(ElementType.BATTERY_PERCENT)
            return TemplateElement(
                id = json.optString("id", UUID.randomUUID().toString()),
                type = type,
                label = json.optString("label", type.defaultLabel),
                visible = json.optBoolean("visible", true),
                xPercent = json.optDouble("xPercent", 4.0).toFloat(),
                yPercent = json.optDouble("yPercent", 8.0).toFloat(),
                widthPercent = json.optDouble("widthPercent", 42.0).toFloat(),
                fontSizeSp = json.optInt("fontSizeSp", type.defaultFontSizeSp),
                colorHex = json.optString("colorHex", "#FFFFFF"),
                isBold = json.optBoolean("isBold", true),
                customText = json.optString("customText", "")
            )
        }
    }
}

data class WidgetCustomTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Cyber Cockpit 4×2",
    val targetSurface: TargetSurface = TargetSurface.APP_WIDGET,
    val gridSize: WidgetGridSize = WidgetGridSize.SIZE_4X2,
    val themePreset: WidgetThemePreset = WidgetThemePreset.CYBER_NEON,
    val backgroundColorHex: String = "#0B0F19",
    val backgroundGradientEndHex: String = "#131D31",
    val accentColorHex: String = "#00E676",
    val textColorHex: String = "#F8FAFC",
    val borderColorHex: String = "#1E3A3A",
    val borderWidthDp: Float = 1.5f,
    val backgroundImagePath: String? = null,
    val backgroundImageOpacity: Float = 0.85f,
    val cornerRadiusDp: Int = 22,
    val paddingDp: Int = 14,
    val clickAction: ClickAction = ClickAction.OPEN_COCKPIT,
    val widgetSlotIndex: Int = 0, // 0 = Main, 1..4 = Custom Widget Slots
    val useWidgetStyleNotification: Boolean = true,
    val tileConfig: TileCustomConfig = TileCustomConfig(),
    val elements: List<TemplateElement> = defaultElementsForSize(TargetSurface.APP_WIDGET, WidgetGridSize.SIZE_4X2),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        elements.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("targetSurface", targetSurface.name)
            put("gridSize", gridSize.name)
            put("themePreset", themePreset.name)
            put("backgroundColorHex", backgroundColorHex)
            put("backgroundGradientEndHex", backgroundGradientEndHex)
            put("accentColorHex", accentColorHex)
            put("textColorHex", textColorHex)
            put("borderColorHex", borderColorHex)
            put("borderWidthDp", borderWidthDp.toDouble())
            put("backgroundImagePath", backgroundImagePath ?: "")
            put("backgroundImageOpacity", backgroundImageOpacity.toDouble())
            put("cornerRadiusDp", cornerRadiusDp)
            put("paddingDp", paddingDp)
            put("clickAction", clickAction.name)
            put("widgetSlotIndex", widgetSlotIndex)
            put("useWidgetStyleNotification", useWidgetStyleNotification)
            put("tileConfig", tileConfig.toJson())
            put("elements", arr)
            put("updatedAtMs", updatedAtMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): WidgetCustomTemplate {
            val surface = runCatching {
                TargetSurface.valueOf(json.optString("targetSurface", TargetSurface.APP_WIDGET.name))
            }.getOrDefault(TargetSurface.APP_WIDGET)

            val size = runCatching {
                WidgetGridSize.valueOf(json.optString("gridSize", WidgetGridSize.SIZE_4X2.name))
            }.getOrDefault(WidgetGridSize.SIZE_4X2)

            val preset = runCatching {
                WidgetThemePreset.valueOf(json.optString("themePreset", WidgetThemePreset.CYBER_NEON.name))
            }.getOrDefault(WidgetThemePreset.CYBER_NEON)

            val clickAct = runCatching {
                ClickAction.valueOf(json.optString("clickAction", ClickAction.OPEN_COCKPIT.name))
            }.getOrDefault(ClickAction.OPEN_COCKPIT)

            val elemArr = json.optJSONArray("elements")
            val elemList = mutableListOf<TemplateElement>()
            if (elemArr != null) {
                for (i in 0 until elemArr.length()) {
                    val obj = elemArr.optJSONObject(i) ?: continue
                    elemList.add(TemplateElement.fromJson(obj))
                }
            }

            return WidgetCustomTemplate(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Custom Template"),
                targetSurface = surface,
                gridSize = size,
                themePreset = preset,
                backgroundColorHex = json.optString("backgroundColorHex", preset.bgHex),
                backgroundGradientEndHex = json.optString("backgroundGradientEndHex", preset.bgGradientEnd),
                accentColorHex = json.optString("accentColorHex", preset.accentHex),
                textColorHex = json.optString("textColorHex", preset.textHex),
                borderColorHex = json.optString("borderColorHex", preset.borderHex),
                borderWidthDp = json.optDouble("borderWidthDp", 1.5).toFloat(),
                backgroundImagePath = json.optString("backgroundImagePath").takeIf { it.isNotBlank() },
                backgroundImageOpacity = json.optDouble("backgroundImageOpacity", 0.85).toFloat(),
                cornerRadiusDp = json.optInt("cornerRadiusDp", 22),
                paddingDp = json.optInt("paddingDp", 14),
                clickAction = clickAct,
                widgetSlotIndex = json.optInt("widgetSlotIndex", 0),
                useWidgetStyleNotification = json.optBoolean("useWidgetStyleNotification", true),
                tileConfig = TileCustomConfig.fromJson(json.optJSONObject("tileConfig")),
                elements = if (elemList.isNotEmpty()) elemList else defaultElementsForSize(surface, size),
                updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }

        fun defaultElementsFor(surface: TargetSurface): List<TemplateElement> {
            return defaultElementsForSize(surface, WidgetGridSize.SIZE_4X2)
        }

        fun defaultElementsForSize(surface: TargetSurface, gridSize: WidgetGridSize): List<TemplateElement> {
            if (surface == TargetSurface.STATUS_NOTIFICATION) {
                return listOf(
                    TemplateElement(type = ElementType.VEHICLE_TITLE, label = "Vehicle Header", xPercent = 4f, yPercent = 8f, fontSizeSp = 13, colorHex = "#00E5FF", isBold = true),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Status Badge", xPercent = 66f, yPercent = 8f, fontSizeSp = 11, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery SoC %", xPercent = 4f, yPercent = 30f, fontSizeSp = 26, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 4f, yPercent = 64f, widthPercent = 40f, fontSizeSp = 6, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Cluster DTE", xPercent = 54f, yPercent = 30f, fontSizeSp = 15, colorHex = "#F1F5F9", isBold = true),
                    TemplateElement(type = ElementType.TRUE_RANGE_ECO_POWER, label = "Learned True Range", xPercent = 54f, yPercent = 54f, fontSizeSp = 11, colorHex = "#94A3B8", isBold = false),
                    TemplateElement(type = ElementType.LAST_PARKED_LOCATION, label = "Locality Pin", xPercent = 4f, yPercent = 76f, fontSizeSp = 11, colorHex = "#CBD5E1", isBold = false),
                    TemplateElement(type = ElementType.LAST_SEEN_TIMESTAMP, label = "Last Seen / ETA", xPercent = 54f, yPercent = 76f, fontSizeSp = 10, colorHex = "#64748B", isBold = false)
                )
            }

            if (surface == TargetSurface.CONTROL_CENTER_TILE) {
                return listOf(
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Tile Top Metric", xPercent = 18f, yPercent = 20f, fontSizeSp = 22, colorHex = "#FFFFFF", isBold = true),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Tile Bottom Metric", xPercent = 18f, yPercent = 58f, fontSizeSp = 16, colorHex = "#00E5FF", isBold = true)
                )
            }

            return when (gridSize) {
                WidgetGridSize.SIZE_1X1 -> listOf(
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery %", xPercent = 12f, yPercent = 14f, fontSizeSp = 26, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 12f, yPercent = 52f, widthPercent = 76f, fontSizeSp = 6, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Range km", xPercent = 12f, yPercent = 66f, fontSizeSp = 13, colorHex = "#F8FAFC", isBold = true)
                )
                WidgetGridSize.SIZE_2X1 -> listOf(
                    TemplateElement(type = ElementType.VEHICLE_TITLE, label = "Vehicle Header", xPercent = 6f, yPercent = 10f, fontSizeSp = 11, colorHex = "#00E5FF", isBold = true),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Status Badge", xPercent = 56f, yPercent = 10f, fontSizeSp = 10, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery %", xPercent = 6f, yPercent = 34f, fontSizeSp = 24, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Cluster DTE", xPercent = 54f, yPercent = 40f, fontSizeSp = 14, colorHex = "#F8FAFC", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 6f, yPercent = 78f, widthPercent = 88f, fontSizeSp = 6, colorHex = "#00E676", isBold = false)
                )
                WidgetGridSize.SIZE_2X2 -> listOf(
                    TemplateElement(type = ElementType.VEHICLE_TITLE, label = "Vehicle Header", xPercent = 6f, yPercent = 7f, fontSizeSp = 12, colorHex = "#00E5FF", isBold = true),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Status Badge", xPercent = 6f, yPercent = 20f, fontSizeSp = 10, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery %", xPercent = 6f, yPercent = 34f, fontSizeSp = 28, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 6f, yPercent = 58f, widthPercent = 86f, fontSizeSp = 6, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Cluster DTE", xPercent = 6f, yPercent = 67f, fontSizeSp = 14, colorHex = "#F1F5F9", isBold = true),
                    TemplateElement(type = ElementType.LAST_PARKED_LOCATION, label = "Locality Pin", xPercent = 6f, yPercent = 82f, fontSizeSp = 10, colorHex = "#94A3B8", isBold = false)
                )
                WidgetGridSize.SIZE_3X1, WidgetGridSize.SIZE_4X1 -> listOf(
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery %", xPercent = 4f, yPercent = 18f, fontSizeSp = 26, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 4f, yPercent = 72f, widthPercent = 34f, fontSizeSp = 6, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Cluster DTE", xPercent = 44f, yPercent = 18f, fontSizeSp = 15, colorHex = "#F8FAFC", isBold = true),
                    TemplateElement(type = ElementType.TRUE_RANGE_ECO_POWER, label = "True Range", xPercent = 44f, yPercent = 54f, fontSizeSp = 10, colorHex = "#94A3B8", isBold = false),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Status Badge", xPercent = 74f, yPercent = 18f, fontSizeSp = 10, colorHex = "#00E676", isBold = true)
                )
                WidgetGridSize.SIZE_3X2, WidgetGridSize.SIZE_4X2 -> listOf(
                    TemplateElement(type = ElementType.VEHICLE_TITLE, label = "Vehicle Header", xPercent = 4f, yPercent = 7f, fontSizeSp = 13, colorHex = "#00E5FF", isBold = true),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Status Badge", xPercent = 68f, yPercent = 7f, fontSizeSp = 11, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery SoC %", xPercent = 4f, yPercent = 28f, fontSizeSp = 28, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 4f, yPercent = 60f, widthPercent = 42f, fontSizeSp = 6, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Cluster DTE", xPercent = 54f, yPercent = 28f, fontSizeSp = 15, colorHex = "#F1F5F9", isBold = true),
                    TemplateElement(type = ElementType.TRUE_RANGE_ECO_POWER, label = "Learned True Range", xPercent = 54f, yPercent = 50f, fontSizeSp = 10, colorHex = "#94A3B8", isBold = false),
                    TemplateElement(type = ElementType.LAST_PARKED_LOCATION, label = "Locality Pin", xPercent = 4f, yPercent = 76f, fontSizeSp = 11, colorHex = "#CBD5E1", isBold = false),
                    TemplateElement(type = ElementType.LAST_SEEN_TIMESTAMP, label = "Last Seen Time", xPercent = 58f, yPercent = 76f, fontSizeSp = 10, colorHex = "#64748B", isBold = false)
                )
                WidgetGridSize.SIZE_4X3 -> listOf(
                    TemplateElement(type = ElementType.VEHICLE_TITLE, label = "Vehicle Header", xPercent = 4f, yPercent = 5f, fontSizeSp = 14, colorHex = "#00E5FF", isBold = true),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Status Badge", xPercent = 68f, yPercent = 5f, fontSizeSp = 11, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery SoC %", xPercent = 4f, yPercent = 20f, fontSizeSp = 32, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 4f, yPercent = 44f, widthPercent = 44f, fontSizeSp = 7, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Cluster DTE", xPercent = 54f, yPercent = 22f, fontSizeSp = 16, colorHex = "#F1F5F9", isBold = true),
                    TemplateElement(type = ElementType.TRUE_RANGE_ECO_POWER, label = "Learned True Range", xPercent = 54f, yPercent = 38f, fontSizeSp = 11, colorHex = "#94A3B8", isBold = false),
                    TemplateElement(type = ElementType.CHARGING_ETA_OR_TIME, label = "Charging ETA 80% / Full", xPercent = 4f, yPercent = 56f, fontSizeSp = 11, colorHex = "#00E5FF", isBold = false),
                    TemplateElement(type = ElementType.ODOMETER_KM, label = "Odometer Reading", xPercent = 54f, yPercent = 56f, fontSizeSp = 11, colorHex = "#CBD5E1", isBold = false),
                    TemplateElement(type = ElementType.TRIP_A_B_DISTANCE, label = "Trip A / B", xPercent = 4f, yPercent = 70f, fontSizeSp = 11, colorHex = "#94A3B8", isBold = false),
                    TemplateElement(type = ElementType.LAST_PARKED_LOCATION, label = "Locality Pin", xPercent = 4f, yPercent = 84f, fontSizeSp = 11, colorHex = "#CBD5E1", isBold = false),
                    TemplateElement(type = ElementType.LAST_SEEN_TIMESTAMP, label = "Last Seen Time", xPercent = 58f, yPercent = 84f, fontSizeSp = 10, colorHex = "#64748B", isBold = false)
                )
            }
        }
    }
}
