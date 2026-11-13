package com.mistbell.tavern.v3.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

internal fun isBackupCredentialKey(key: String): Boolean {
    val normalized = key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(java.util.Locale.ROOT)
    // Existing token-limit preferences are numeric controls, not secrets.
    if (normalized in setOf("chat_context_tokens", "chat_max_tokens")) return false
    val compact = normalized.replace("_", "")
    val credentialMarkers = setOf("credential", "secret", "apikey", "password", "keystore", "privatekey")
    val words = normalized.split(Regex("[^a-z0-9]+"))
    return credentialMarkers.any(compact::contains) || "token" in compact ||
        words.any { it == "auth" || it == "bearer" || it == "authorization" }
}

/** Pure typed preference encoding/decoding shared by backup export and restore. */
internal object BackupPreferenceCodec {
    fun encode(values: Map<String, *>): JSONObject {
        val settings = JSONObject()
        val types = JSONObject()
        val preferences =
            values.toSortedMap().filterKeys { key ->
                key != BackupSource.SETTING_TYPES_KEY && !isBackupCredentialKey(key)
            }
        for ((key, value) in preferences) {
            val encodedValue =
                when (value) {
                    is String -> value to "string"
                    is Boolean -> value to "boolean"
                    is Int -> value to "int"
                    is Long -> value to "long"
                    is Float -> {
                        require(value.isFinite()) { "Backup preference contains a non-finite float." }
                        value.toDouble() to "float"
                    }
                    is Set<*> -> {
                        require(value.all { it is String }) {
                            "Backup preference string set contains a non-string value."
                        }
                        org.json.JSONArray(value.filterIsInstance<String>().sorted()) to "string_set"
                    }
                    else -> null
                } ?: continue
            settings.put(key, encodedValue.first)
            types.put(key, encodedValue.second)
        }
        settings.put(BackupSource.SETTING_TYPES_KEY, types)
        return settings
    }

    fun decode(
        json: JSONObject,
        previous: Map<String, *> = emptyMap<String, Any>(),
    ): Map<String, Any> {
        val types =
            if (json.has(BackupSource.SETTING_TYPES_KEY)) {
                json.optJSONObject(BackupSource.SETTING_TYPES_KEY)
                    ?: throw IllegalArgumentException("Backup preference type metadata is invalid.")
            } else {
                null
            }
        val result = linkedMapOf<String, Any>()
        val valueKeys = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key == BackupSource.SETTING_TYPES_KEY || isBackupCredentialKey(key)) continue
            valueKeys += key
            val value = json.get(key)
            val encodedType = types?.opt(key)
            val type =
                when {
                    types == null -> inferredType(previous[key], value)
                    encodedType is String -> encodedType
                    else -> throw IllegalArgumentException("Backup preference type metadata is missing or invalid.")
                }
            result[key] = decodeValue(key, value, type)
        }
        if (types != null) {
            val typeIterator = types.keys()
            while (typeIterator.hasNext()) {
                val key = typeIterator.next()
                require(key in valueKeys) { "Backup preference type metadata has no matching value." }
            }
        }
        return result
    }

    private fun decodeValue(
        key: String,
        value: Any,
        type: String?,
    ): Any =
        when (type) {
            "string" -> value as? String ?: invalidPreference(key)
            "boolean" -> value as? Boolean ?: invalidPreference(key)
            "int" -> exactNumber(value, key).intValueExact()
            "long" -> exactNumber(value, key).longValueExact()
            "float" -> {
                val number = exactNumber(value, key).toDouble()
                val restored = number.toFloat()
                require(number.isFinite() && restored.isFinite()) {
                    "Backup preference '$key' is not a finite float."
                }
                restored
            }
            "string_set" -> {
                val array = value as? org.json.JSONArray ?: invalidPreference(key)
                (0 until array.length()).map { index ->
                    array.get(index) as? String ?: invalidPreference(key)
                }.toSet()
            }
            else -> throw IllegalArgumentException("Backup preference '$key' has an unsupported type.")
        }

    private fun exactNumber(
        value: Any,
        key: String,
    ): java.math.BigDecimal {
        require(value is Number) { "Backup preference '$key' is not numeric." }
        return try {
            java.math.BigDecimal(value.toString())
        } catch (failure: NumberFormatException) {
            throw IllegalArgumentException("Backup preference '$key' is not a valid number.", failure)
        }
    }

    private fun inferredType(
        previous: Any?,
        value: Any,
    ): String? =
        when (previous) {
            is String -> if (value is String) "string" else valueType(value)
            is Boolean -> if (value is Boolean) "boolean" else valueType(value)
            is Int -> if (value is Number) "int" else valueType(value)
            is Long -> if (value is Number) "long" else valueType(value)
            is Float -> if (value is Number) "float" else valueType(value)
            is Set<*> -> if (value is org.json.JSONArray) "string_set" else valueType(value)
            else -> valueType(value)
        }

    private fun valueType(value: Any): String? =
        when (value) {
            is String -> "string"
            is Boolean -> "boolean"
            is org.json.JSONArray -> "string_set"
            is Int -> "int"
            is Long -> "long"
            is Float, is Double -> "float"
            else -> null
        }

    private fun invalidPreference(key: String): Nothing =
        throw IllegalArgumentException("Backup preference '$key' has a value that does not match its type.")
}

/** Resolves the explicitly supported Mistbell 3.0 data sources without including model assets. */
class BackupSource(private val context: Context) {
    fun collect(snapshotDirectory: File): Map<String, File> {
        require(snapshotDirectory.isDirectory || snapshotDirectory.mkdirs()) {
            "Backup snapshot directory could not be created."
        }
        val result = linkedMapOf<String, File>()
        includeDatabase(CHAT_DATABASE_NAME, snapshotDirectory, result)
        includeDatabase(SESSION_DATABASE_NAME, snapshotDirectory, result)

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
        addDirectory(result, File(context.filesDir, "worldbooks"), "data/worldbooks") {
            it.endsWith(".json", ignoreCase = true)
        }
        addDirectory(result, File(context.filesDir, "characters"), "data/characters") { relative ->
            relative.equals("characters.json", ignoreCase = true) ||
                relative.endsWith(".json", ignoreCase = true) || CHARACTER_ART_PATH.matches(relative)
        }
        addDirectory(result, File(context.filesDir, "generated-images"), "data/generated-images") { true }

        val preferences = context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        val settings = BackupPreferenceCodec.encode(preferences.all)
        val settingsFile = File(snapshotDirectory, SETTINGS_BACKUP_FILE)
        settingsFile.writeText(settings.toString(), Charsets.UTF_8)
        result["data/settings.json"] = settingsFile
        return result
    }

    private fun includeDatabase(
        name: String,
        snapshotDirectory: File,
        result: MutableMap<String, File>,
    ) {
        val database = context.getDatabasePath(name)
        if (!database.isFile) return
        val snapshot = File(snapshotDirectory, name)
        val source = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val escaped = snapshot.absolutePath.replace("'", "''")
            source.execSQL("VACUUM INTO '$escaped'")
        } finally {
            source.close()
        }
        result["data/$name"] = snapshot
    }

    private fun addDirectory(
        result: MutableMap<String, File>,
        directory: File,
        archivePrefix: String,
        include: (String) -> Boolean,
    ) {
        if (!directory.exists()) return
        require(directory.isDirectory) { "Backup source path is not a directory." }
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            if (include(relative)) result["$archivePrefix/$relative"] = file
        }
    }

    companion object {
        fun isMemoryFileName(name: String): Boolean {
            return name in MEMORY_FILE_NAMES || MEMORY_FILE_PATTERNS.any { it.matches(name) }
        }

        const val CHAT_DATABASE_NAME = "mistbell-v3-chat.db"
        const val SESSION_DATABASE_NAME = "mistbell-v3-sessions.db"
        const val SETTINGS_PREFERENCES = "mistbell-v3-settings"
        const val SETTINGS_BACKUP_FILE = "settings.json"
        internal const val SETTING_TYPES_KEY = "_mistbell_preference_types"
        private val MEMORY_FILE_NAMES =
            setOf(
                "mistbell-v3-memory-texts.bin",
                "mistbell-v3-memory-facts.bin",
                "mistbell-v3-memory-jobs.bin",
                "mistbell-v3-memory-extraction-queue.bin",
                "mistbell-v3-memory-extraction-queue.bin.scopes.json",
                "mistbell-v3-temporal-facts.bin",
                "mistbell-v3-vectors.bin",
            )
        private val CHARACTER_ART_PATH = Regex("art/[a-f0-9]{64}-(avatar|portrait)\\.img")
        private val MEMORY_FILE_PATTERNS =
            listOf(
                Regex("mistbell-v3-memory-texts-[a-f0-9]{24}\\.bin"),
                Regex("mistbell-v3-memory-facts-[a-f0-9]{24}\\.bin"),
                Regex("mistbell-v3-memory-jobs-[a-f0-9]{24}\\.bin"),
                Regex("mistbell-v3-memory-overrides-[a-f0-9]{24}\\.json"),
                Regex("mistbell-v3-temporal-facts-[a-f0-9]{24}\\.bin"),
                Regex("mistbell-v3-vectors-[a-f0-9]{24}\\.bin"),
            )
    }
}
