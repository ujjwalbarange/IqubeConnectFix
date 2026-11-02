package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TargetSurface(val title: String, val subtitle: String) {
    APP_WIDGET("Home Screen Widget", "Interactive resizable launcher widget"),
    CONTROL_CENTER_TILE("Quick Settings Tile", "Control Center quick toggle / speed status"),
    STATUS_NOTIFICATION("Persistent Notification", "Ongoing status bar telemetry & actions")
}

enum class WidgetThemePreset(
    val title: String,
    val bgHex: String,
    val bgGradientEnd: String,
    val accentHex: String,
    val textHex: String
) {
    CYBER_NEON("Cyber Neon", "#0B0F19", "#0F172A", "#00E676", "#F8FAFC"),
    ELECTRIC_CYAN("Electric Cyan", "#070D18", "#0E1A2D", "#00E5FF", "#F1F5F9"),
    SUNSET_CORAL("Sunset Coral", "#12080D", "#23111A", "#FF5252", "#FFF1F2"),
    AMBER_VOLT("Amber Volt", "#121008", "#241C0A", "#FFB300", "#FFFBEB"),
    STEALTH_OLED("Stealth OLED", "#000000", "#000000", "#FFFFFF", "#E2E8F0"),
    GLASS_SLATE("Glass Slate", "#1E293B", "#0F172A", "#38BDF8", "#F8FAFC"),
    CANVA_CUSTOM("Canva / Custom Dump", "#0F172A", "#1E293B", "#00E5FF", "#FFFFFF")
}

enum class ClickAction(val title: String, val description: String) {
    OPEN_COCKPIT("Open Cockpit", "Launches the live telemetry cockpit dashboard"),
    TRIGGER_BLE_CONNECT("Trigger BLE Connect", "Instantly starts scanning & auto-connecting to scooter"),
    TOGGLE_RIDE_MODE("Toggle Ride Mode", "Switches between Eco and Power true range modes"),
    OPEN_SAVED_TRIPS("Open Saved Trips", "Opens saved trips & charging history"),
    OPEN_STUDIO_SANDBOX("Open Customization Studio", "Opens the template editing sandbox")
}

enum class ElementType(val defaultLabel: String) {
    BATTERY_PERCENT("Battery SoC %"),
    BATTERY_BAR("Battery Progress Bar"),
    RANGE_KM("Cluster DTE Range"),
    TRUE_RANGE_ECO_POWER("Learned True Range (Eco/Pwr)"),
    STATUS_BADGE("Connection & Charging Badge"),
    VEHICLE_TITLE("Vehicle Name / Header"),
    CHARGING_ETA_OR_TIME("Charging ETA to 80% / Full"),
    LAST_PARKED_LOCATION("Parked Location & Last Seen"),
    CUSTOM_TEXT("Custom Accent Tagline")
}

data class TemplateElement(
    val id: String = UUID.randomUUID().toString(),
    val type: ElementType,
    val label: String = type.defaultLabel,
    val visible: Boolean = true,
    val xPercent: Float = 0f, // 0f..100f
    val yPercent: Float = 0f, // 0f..100f
    val fontSizeSp: Int = 14,
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
                xPercent = json.optDouble("xPercent", 0.0).toFloat(),
                yPercent = json.optDouble("yPercent", 0.0).toFloat(),
                fontSizeSp = json.optInt("fontSizeSp", 14),
                colorHex = json.optString("colorHex", "#FFFFFF"),
                isBold = json.optBoolean("isBold", true),
                customText = json.optString("customText", "")
            )
        }
    }
}

data class WidgetCustomTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default Cockpit Template",
    val targetSurface: TargetSurface = TargetSurface.APP_WIDGET,
    val themePreset: WidgetThemePreset = WidgetThemePreset.CYBER_NEON,
    val backgroundColorHex: String = "#0B0F19",
    val backgroundGradientEndHex: String = "#0F172A",
    val accentColorHex: String = "#00E676",
    val textColorHex: String = "#F8FAFC",
    val backgroundImagePath: String? = null,
    val backgroundImageOpacity: Float = 0.85f,
    val cornerRadiusDp: Int = 18,
    val paddingDp: Int = 14,
    val clickAction: ClickAction = ClickAction.OPEN_COCKPIT,
    val elements: List<TemplateElement> = defaultElementsFor(TargetSurface.APP_WIDGET),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        elements.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("targetSurface", targetSurface.name)
            put("themePreset", themePreset.name)
            put("backgroundColorHex", backgroundColorHex)
            put("backgroundGradientEndHex", backgroundGradientEndHex)
            put("accentColorHex", accentColorHex)
            put("textColorHex", textColorHex)
            put("backgroundImagePath", backgroundImagePath ?: "")
            put("backgroundImageOpacity", backgroundImageOpacity.toDouble())
            put("cornerRadiusDp", cornerRadiusDp)
            put("paddingDp", paddingDp)
            put("clickAction", clickAction.name)
            put("elements", arr)
            put("updatedAtMs", updatedAtMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): WidgetCustomTemplate {
            val surface = runCatching {
                TargetSurface.valueOf(json.optString("targetSurface", TargetSurface.APP_WIDGET.name))
            }.getOrDefault(TargetSurface.APP_WIDGET)

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
                themePreset = preset,
                backgroundColorHex = json.optString("backgroundColorHex", preset.bgHex),
                backgroundGradientEndHex = json.optString("backgroundGradientEndHex", preset.bgGradientEnd),
                accentColorHex = json.optString("accentColorHex", preset.accentHex),
                textColorHex = json.optString("textColorHex", preset.textHex),
                backgroundImagePath = json.optString("backgroundImagePath").takeIf { it.isNotBlank() },
                backgroundImageOpacity = json.optDouble("backgroundImageOpacity", 0.85).toFloat(),
                cornerRadiusDp = json.optInt("cornerRadiusDp", 18),
                paddingDp = json.optInt("paddingDp", 14),
                clickAction = clickAct,
                elements = if (elemList.isNotEmpty()) elemList else defaultElementsFor(surface),
                updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }

        fun defaultElementsFor(surface: TargetSurface): List<TemplateElement> {
            return when (surface) {
                TargetSurface.APP_WIDGET -> listOf(
                    TemplateElement(type = ElementType.VEHICLE_TITLE, label = "Vehicle Header", xPercent = 4f, yPercent = 6f, fontSizeSp = 13, colorHex = "#00E5FF", isBold = true),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Status Badge", xPercent = 66f, yPercent = 6f, fontSizeSp = 11, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery SoC %", xPercent = 4f, yPercent = 30f, fontSizeSp = 28, colorHex = "#00E676", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_BAR, label = "Battery Bar", xPercent = 4f, yPercent = 58f, fontSizeSp = 6, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Cluster DTE", xPercent = 55f, yPercent = 28f, fontSizeSp = 15, colorHex = "#F1F5F9", isBold = true),
                    TemplateElement(type = ElementType.TRUE_RANGE_ECO_POWER, label = "Learned True Range", xPercent = 55f, yPercent = 48f, fontSizeSp = 10, colorHex = "#94A3B8", isBold = false),
                    TemplateElement(type = ElementType.LAST_PARKED_LOCATION, label = "Locality & Last Seen", xPercent = 4f, yPercent = 75f, fontSizeSp = 10, colorHex = "#64748B", isBold = false),
                    TemplateElement(type = ElementType.CHARGING_ETA_OR_TIME, label = "Charging Time to 80% / Full", xPercent = 4f, yPercent = 90f, fontSizeSp = 10, colorHex = "#00E5FF", isBold = false)
                )
                TargetSurface.CONTROL_CENTER_TILE -> listOf(
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Tile Battery %", xPercent = 10f, yPercent = 25f, fontSizeSp = 16, colorHex = "#FFFFFF", isBold = true),
                    TemplateElement(type = ElementType.RANGE_KM, label = "Tile Range km", xPercent = 10f, yPercent = 60f, fontSizeSp = 13, colorHex = "#00E5FF", isBold = true),
                    TemplateElement(type = ElementType.STATUS_BADGE, label = "Tile Status Indicator", xPercent = 60f, yPercent = 40f, fontSizeSp = 11, colorHex = "#00E676", isBold = true)
                )
                TargetSurface.STATUS_NOTIFICATION -> listOf(
                    TemplateElement(type = ElementType.VEHICLE_TITLE, label = "Notification Header", xPercent = 0f, yPercent = 10f, fontSizeSp = 14, colorHex = "#FFFFFF", isBold = true),
                    TemplateElement(type = ElementType.BATTERY_PERCENT, label = "Battery & Speed", xPercent = 0f, yPercent = 35f, fontSizeSp = 13, colorHex = "#00E676", isBold = false),
                    TemplateElement(type = ElementType.TRUE_RANGE_ECO_POWER, label = "True Range Details", xPercent = 0f, yPercent = 60f, fontSizeSp = 12, colorHex = "#94A3B8", isBold = false),
                    TemplateElement(type = ElementType.CHARGING_ETA_OR_TIME, label = "Charging Progress & Calibration", xPercent = 0f, yPercent = 85f, fontSizeSp = 11, colorHex = "#FFB300", isBold = false)
                )
            }
        }
    }
}
