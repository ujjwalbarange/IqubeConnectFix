package com.example.ble

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.model.WidgetCustomTemplate
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages permanent persistence in Android's shared Documents directory (`Documents/iQubeCockpit/`)
 * so trips, charging intervals, and custom Canva/sandbox widget templates survive
 * complete app uninstalls and re-installs without requiring broad storage permissions.
 */
class IqubeSharedStorageManager private constructor(private val context: Context) {

    private val baseDir: File by lazy {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val target = File(docsDir, DIR_NAME)
        if (!target.exists()) {
            target.mkdirs()
        }
        target
    }

    private val templatesDir: File by lazy {
        val dir = File(baseDir, "templates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    fun getSharedStoragePath(): String = baseDir.absolutePath

    // --- Trips & Charging Session Shared Persistence ---

    fun saveTripsBackup(tripsJson: String): Boolean {
        return runCatching {
            val file = File(baseDir, FILE_TRIPS_BACKUP)
            file.writeText(tripsJson, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun loadTripsBackup(): String? {
        return runCatching {
            val file = File(baseDir, FILE_TRIPS_BACKUP)
            if (file.exists() && file.length() > 0) {
                file.readText(Charsets.UTF_8)
            } else null
        }.getOrNull()
    }

    fun saveChargingBackup(chargingJson: String): Boolean {
        return runCatching {
            val file = File(baseDir, FILE_CHARGING_BACKUP)
            file.writeText(chargingJson, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun loadChargingBackup(): String? {
        return runCatching {
            val file = File(baseDir, FILE_CHARGING_BACKUP)
            if (file.exists() && file.length() > 0) {
                file.readText(Charsets.UTF_8)
            } else null
        }.getOrNull()
    }

    // --- Custom Templates & Canva Asset Persistence ---

    fun saveTemplates(templates: List<WidgetCustomTemplate>): Boolean {
        return runCatching {
            val arr = JSONArray()
            templates.forEach { arr.put(it.toJson()) }
            val file = File(baseDir, FILE_TEMPLATES_BACKUP)
            file.writeText(arr.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun loadTemplates(): List<WidgetCustomTemplate> {
        return runCatching {
            val file = File(baseDir, FILE_TEMPLATES_BACKUP)
            if (file.exists() && file.length() > 0) {
                val text = file.readText(Charsets.UTF_8)
                val arr = JSONArray(text)
                val list = mutableListOf<WidgetCustomTemplate>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(WidgetCustomTemplate.fromJson(obj))
                }
                list
            } else emptyList()
        }.getOrDefault(emptyList())
    }

    fun getSharedDirPath(): String = getSharedStoragePath()

    fun saveActiveTemplate(template: WidgetCustomTemplate): Boolean {
        return runCatching {
            val file = File(baseDir, FILE_ACTIVE_TEMPLATE)
            file.writeText(template.toJson().toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun loadActiveTemplate(): WidgetCustomTemplate {
        return runCatching {
            val file = File(baseDir, FILE_ACTIVE_TEMPLATE)
            if (file.exists() && file.length() > 0) {
                val text = file.readText(Charsets.UTF_8)
                WidgetCustomTemplate.fromJson(org.json.JSONObject(text))
            } else null
        }.getOrNull() ?: WidgetCustomTemplate()
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

        @Volatile
        private var instance: IqubeSharedStorageManager? = null

        fun getInstance(context: Context): IqubeSharedStorageManager {
            return instance ?: synchronized(this) {
                instance ?: IqubeSharedStorageManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
