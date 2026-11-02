package com.example.ble

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.model.TargetSurface
import com.example.model.TileCustomConfig
import com.example.model.WidgetCustomTemplate
import com.example.model.WidgetGridSize
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages permanent persistence in Android's shared Documents directory (`Documents/iQubeCockpit/`)
 * with automatic fallback to app-local storage so trips, charging intervals, widget slots,
 * notification styles, tile styles, and Canva assets survive app uninstalls and updates.
 */
class IqubeSharedStorageManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("iqube_studio_templates_prefs", Context.MODE_PRIVATE)

    private val baseDir: File by lazy {
        val publicDir = runCatching {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val target = File(docsDir, DIR_NAME)
            if (!target.exists()) target.mkdirs()
            if (target.exists() && target.canWrite()) target else null
        }.getOrNull()

        publicDir ?: File(context.filesDir, DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private val templatesDir: File by lazy {
        val dir = File(baseDir, "templates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    fun getSharedStoragePath(): String = "Documents/$DIR_NAME"
    fun getSharedDirPath(): String = getSharedStoragePath()

    private fun writeDualStorage(fileName: String, prefKey: String, content: String): Boolean {
        prefs.edit().putString(prefKey, content).apply()
        return runCatching {
            File(baseDir, fileName).writeText(content, Charsets.UTF_8)
            true
        }.getOrDefault( true)
    }

    private fun readDualStorage(fileName: String, prefKey: String): String? {
        val fromFile = runCatching {
            val file = File(baseDir, fileName)
            if (file.exists() && file.length() > 0) {
                file.readText(Charsets.UTF_8)
            } else null
        }.getOrNull()

        if (!fromFile.isNullOrBlank()) {
            prefs.edit().putString(prefKey, fromFile).apply()
            return fromFile
        }
        return prefs.getString(prefKey, null)
    }

    // --- Trips & Charging Session Shared Persistence ---

    fun saveTripsBackup(tripsJson: String): Boolean {
        return writeDualStorage(FILE_TRIPS_BACKUP, "backup_trips_json", tripsJson)
    }

    fun loadTripsBackup(): String? {
        return readDualStorage(FILE_TRIPS_BACKUP, "backup_trips_json")
    }

    fun saveChargingBackup(chargingJson: String): Boolean {
        return writeDualStorage(FILE_CHARGING_BACKUP, "backup_charging_json", chargingJson)
    }

    fun loadChargingBackup(): String? {
        return readDualStorage(FILE_CHARGING_BACKUP, "backup_charging_json")
    }

    // --- Custom Templates Library (Widgets, Notifications, Tiles) ---

    fun defaultSeededTemplates(): List<WidgetCustomTemplate> {
        return listOf(
            WidgetCustomTemplate(
                id = "default_widget_4x2",
                name = "Cyber Cockpit (4×2)",
                targetSurface = TargetSurface.APP_WIDGET,
                gridSize = WidgetGridSize.SIZE_4X2,
                widgetSlotIndex = 0,
                elements = WidgetCustomTemplate.defaultElementsForSize(TargetSurface.APP_WIDGET, WidgetGridSize.SIZE_4X2)
            ),
            WidgetCustomTemplate(
                id = "default_widget_2x1",
                name = "Compact Pill (2×1)",
                targetSurface = TargetSurface.APP_WIDGET,
                gridSize = WidgetGridSize.SIZE_2X1,
                widgetSlotIndex = 1,
                elements = WidgetCustomTemplate.defaultElementsForSize(TargetSurface.APP_WIDGET, WidgetGridSize.SIZE_2X1)
            ),
            WidgetCustomTemplate(
                id = "default_widget_1x1",
                name = "Mini Cube (1×1)",
                targetSurface = TargetSurface.APP_WIDGET,
                gridSize = WidgetGridSize.SIZE_1X1,
                widgetSlotIndex = 2,
                elements = WidgetCustomTemplate.defaultElementsForSize(TargetSurface.APP_WIDGET, WidgetGridSize.SIZE_1X1)
            ),
            WidgetCustomTemplate(
                id = "default_notif_style",
                name = "Cockpit Widget Notification",
                targetSurface = TargetSurface.STATUS_NOTIFICATION,
                gridSize = WidgetGridSize.SIZE_4X2,
                useWidgetStyleNotification = true,
                elements = WidgetCustomTemplate.defaultElementsForSize(TargetSurface.STATUS_NOTIFICATION, WidgetGridSize.SIZE_4X2)
            ),
            WidgetCustomTemplate(
                id = "default_tile_style",
                name = "Battery % + DTE Range Tile",
                targetSurface = TargetSurface.CONTROL_CENTER_TILE,
                gridSize = WidgetGridSize.SIZE_1X1,
                tileConfig = TileCustomConfig(),
                elements = WidgetCustomTemplate.defaultElementsForSize(TargetSurface.CONTROL_CENTER_TILE, WidgetGridSize.SIZE_1X1)
            )
        )
    }

    fun saveTemplates(templates: List<WidgetCustomTemplate>): Boolean {
        val arr = JSONArray()
        templates.forEach { arr.put(it.toJson()) }
        return writeDualStorage(FILE_TEMPLATES_BACKUP, "saved_templates_json", arr.toString())
    }

    fun loadTemplates(): List<WidgetCustomTemplate> {
        val raw = readDualStorage(FILE_TEMPLATES_BACKUP, "saved_templates_json")
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val arr = JSONArray(raw)
                val list = mutableListOf<WidgetCustomTemplate>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(WidgetCustomTemplate.fromJson(obj))
                }
                list
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        val seeded = defaultSeededTemplates()
        saveTemplates(seeded)
        return seeded
    }

    fun upsertTemplate(template: WidgetCustomTemplate): List<WidgetCustomTemplate> {
        val current = loadTemplates().toMutableList()
        val idx = current.indexOfFirst { it.id == template.id }
        val updated = template.copy(updatedAtMs = System.currentTimeMillis())
        if (idx >= 0) {
            current[idx] = updated
        } else {
            current.add(0, updated)
        }
        saveTemplates(current)
        saveActiveTemplateForSurface(updated)
        return current
    }

    fun deleteTemplate(templateId: String): List<WidgetCustomTemplate> {
        val current = loadTemplates().filterNot { it.id == templateId }
        val finalList = current.ifEmpty { defaultSeededTemplates() }
        saveTemplates(finalList)
        return finalList
    }

    fun saveActiveTemplate(template: WidgetCustomTemplate): Boolean {
        return saveActiveTemplateForSurface(template)
    }

    fun loadActiveTemplate(): WidgetCustomTemplate {
        return loadActiveTemplateForSurface(TargetSurface.APP_WIDGET)
    }

    fun saveActiveTemplateForSurface(template: WidgetCustomTemplate): Boolean {
        val (fileName, prefKey) = when (template.targetSurface) {
            TargetSurface.APP_WIDGET -> FILE_ACTIVE_TEMPLATE to "active_widget_template_json"
            TargetSurface.STATUS_NOTIFICATION -> FILE_ACTIVE_NOTIF_TEMPLATE to "active_notif_template_json"
            TargetSurface.CONTROL_CENTER_TILE -> FILE_ACTIVE_TILE_TEMPLATE to "active_tile_template_json"
        }
        if (template.targetSurface == TargetSurface.APP_WIDGET && template.widgetSlotIndex in 0..4) {
            prefs.edit().putString("slot_template_${template.widgetSlotIndex}", template.toJson().toString()).apply()
        }
        return writeDualStorage(fileName, prefKey, template.toJson().toString())
    }

    fun loadActiveTemplateForSurface(surface: TargetSurface): WidgetCustomTemplate {
        val (fileName, prefKey) = when (surface) {
            TargetSurface.APP_WIDGET -> FILE_ACTIVE_TEMPLATE to "active_widget_template_json"
            TargetSurface.STATUS_NOTIFICATION -> FILE_ACTIVE_NOTIF_TEMPLATE to "active_notif_template_json"
            TargetSurface.CONTROL_CENTER_TILE -> FILE_ACTIVE_TILE_TEMPLATE to "active_tile_template_json"
        }
        val raw = readDualStorage(fileName, prefKey)
        if (!raw.isNullOrBlank()) {
            runCatching {
                return WidgetCustomTemplate.fromJson(JSONObject(raw))
            }
        }
        return loadTemplates().firstOrNull { it.targetSurface == surface }
            ?: defaultSeededTemplates().first { it.targetSurface == surface }
    }

    fun bindAppWidgetIdToTemplate(appWidgetId: Int, template: WidgetCustomTemplate) {
        prefs.edit()
            .putString("appwidget_template_$appWidgetId", template.toJson().toString())
            .apply()
    }

    fun getTemplateForAppWidget(appWidgetId: Int, slotIndex: Int = 0): WidgetCustomTemplate {
        val boundJson = prefs.getString("appwidget_template_$appWidgetId", null)
        if (!boundJson.isNullOrBlank()) {
            runCatching { return WidgetCustomTemplate.fromJson(JSONObject(boundJson)) }
        }
        val slotJson = prefs.getString("slot_template_$slotIndex", null)
        if (!slotJson.isNullOrBlank()) {
            runCatching { return WidgetCustomTemplate.fromJson(JSONObject(slotJson)) }
        }
        val matchingSlot = loadTemplates().firstOrNull {
            it.targetSurface == TargetSurface.APP_WIDGET && it.widgetSlotIndex == slotIndex
        }
        return matchingSlot ?: loadActiveTemplateForSurface(TargetSurface.APP_WIDGET)
    }

    /**
     * Imports an image file (e.g., exported from Canva or photo gallery via Android Photo Picker)
     * and saves it to permanent shared storage (`Documents/iQubeCockpit/templates/`).
     * Returns the permanent absolute file path.
     */
    fun importImageToSharedTemplates(imageUri: Uri): String? {
        return runCatching {
            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)
            if (inputStream != null) {
                val destFile = File(templatesDir, "canva_template_${System.currentTimeMillis()}.png")
                FileOutputStream(destFile).use { output ->
                    inputStream.copyTo(output)
                }
                destFile.absolutePath
            } else null
        }.getOrNull()
    }

    fun importCanvaTemplateImage(imageUri: Uri): String? = importImageToSharedTemplates(imageUri)

    companion object {
        const val DIR_NAME = "iQubeCockpit"
        const val FILE_TRIPS_BACKUP = "iqube_trips_backup.json"
        const val FILE_CHARGING_BACKUP = "iqube_charging_backup.json"
        const val FILE_TEMPLATES_BACKUP = "iqube_templates_backup.json"
        const val FILE_ACTIVE_TEMPLATE = "iqube_active_template.json"
        const val FILE_ACTIVE_NOTIF_TEMPLATE = "iqube_active_notif_template.json"
        const val FILE_ACTIVE_TILE_TEMPLATE = "iqube_active_tile_template.json"

        @Volatile
        private var instance: IqubeSharedStorageManager? = null

        fun getInstance(context: Context): IqubeSharedStorageManager {
            return instance ?: synchronized(this) {
                instance ?: IqubeSharedStorageManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
