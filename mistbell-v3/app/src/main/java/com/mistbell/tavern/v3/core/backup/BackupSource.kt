package com.mistbell.tavern.v3.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/** Resolves the explicitly supported Mistbell 3.0 data sources without including model assets. */
class BackupSource(private val context: Context) {
    fun collect(snapshotDirectory: File): Map<String, File> {
        require(snapshotDirectory.isDirectory || snapshotDirectory.mkdirs()) {
            "Backup snapshot directory could not be created."
        }
        val result = linkedMapOf<String, File>()
        val db = context.getDatabasePath(CHAT_DATABASE_NAME)
        if (db.isFile) {
            val snapshot = File(snapshotDirectory, CHAT_DATABASE_NAME)
            val source = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val escaped = snapshot.absolutePath.replace("'", "''")
                source.execSQL("VACUUM INTO '$escaped'")
            } finally {
                source.close()
            }
            result["data/$CHAT_DATABASE_NAME"] = snapshot
        }

        MEMORY_FILE_NAMES.forEach { name ->
            context.filesDir.listFiles().orEmpty().filter { it.isFile && it.name == name }.forEach { file ->
                result["data/memory/${file.name}"] = file
            }
        }
        // Scope-specific stores append a fixed-width SHA-256 suffix before their extension.
        context.filesDir.listFiles().orEmpty().filter { file ->
            file.isFile && MEMORY_FILE_PATTERNS.any { pattern -> pattern.matches(file.name) }
        }.forEach { file ->
            result["data/memory/${file.name}"] = file
        }
        addDirectory(result, File(context.filesDir, "worldbooks"), "data/worldbooks", "json")
        addDirectory(result, File(context.filesDir, "characters"), "data/characters", "json")
        addDirectory(result, File(context.filesDir, "generated-images"), "data/generated-images", null)

        val preferences = context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        val settings = JSONObject()
        preferences.all.toSortedMap().forEach { (key, value) ->
            if (!isCredentialKey(key)) {
                when (value) {
                    is String -> settings.put(key, value)
                    is Boolean, is Int, is Long, is Float -> settings.put(key, value)
                    is Set<*> -> settings.put(key, org.json.JSONArray(value.filterIsInstance<String>()))
                }
            }
        }
        val settingsFile = File(snapshotDirectory, SETTINGS_BACKUP_FILE)
        settingsFile.writeText(settings.toString(), Charsets.UTF_8)
        result["data/settings.json"] = settingsFile
        return result
    }

    private fun addDirectory(
        result: MutableMap<String, File>,
        directory: File,
        archivePrefix: String,
        extension: String?,
    ) {
        if (!directory.exists()) return
        require(directory.isDirectory) { "Backup source path is not a directory." }
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            if (extension == null || file.extension.equals(extension, ignoreCase = true)) {
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                result["$archivePrefix/$relative"] = file
            }
        }
    }

    private fun isCredentialKey(key: String): Boolean {
        val normalized = key.lowercase()
        return listOf("credential", "secret", "apikey", "api_key", "api-key", "password", "token", "keystore", "privatekey", "private_key")
            .any(normalized::contains)
    }

    companion object {
        fun isMemoryFileName(name: String): Boolean =
            name in MEMORY_FILE_NAMES || MEMORY_FILE_PATTERNS.any { it.matches(name) }

        const val CHAT_DATABASE_NAME = "mistbell-v3-chat.db"
        const val SETTINGS_PREFERENCES = "mistbell-v3-settings"
        const val SETTINGS_BACKUP_FILE = "settings.json"
        private val MEMORY_FILE_NAMES = setOf(
            "mistbell-v3-memory-texts.bin",
            "mistbell-v3-memory-facts.bin",
            "mistbell-v3-memory-jobs.bin",
            "mistbell-v3-temporal-facts.bin",
            "mistbell-v3-vectors.bin",
        )
        private val MEMORY_FILE_PATTERNS = listOf(
            Regex("mistbell-v3-memory-texts-[a-f0-9]{24}\\.bin"),
            Regex("mistbell-v3-memory-facts-[a-f0-9]{24}\\.bin"),
            Regex("mistbell-v3-memory-jobs-[a-f0-9]{24}\\.bin"),
            Regex("mistbell-v3-temporal-facts-[a-f0-9]{24}\\.bin"),
            Regex("mistbell-v3-vectors-[a-f0-9]{24}\\.bin"),
        )
    }
}
