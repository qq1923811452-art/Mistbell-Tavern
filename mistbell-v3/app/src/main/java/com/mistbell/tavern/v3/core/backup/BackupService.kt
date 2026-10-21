package com.mistbell.tavern.v3.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Export, preview and restore orchestration. No UI entry points are added in this change. */
class BackupService(
    context: Context,
    private val appVersion: String = "3.0.0-dev",
    private val clock: () -> Instant = Instant::now,
    private val writer: BackupArchiveWriter = BackupArchiveWriter(),
    private val reader: BackupArchiveReader = BackupArchiveReader(),
    private val validator: BackupValidatorFacade = BackupValidatorFacade(),
) {
    private val appContext = context.applicationContext
    private val source = BackupSource(appContext)

    suspend fun export(destination: File): BackupArchiveResult = operationMutex.withLock {
        val started = clock()
        val staging = File(appContext.cacheDir, "backup-export-${UUID.randomUUID()}")
        try {
            require(staging.mkdirs()) { "Backup temporary directory could not be created." }
            writer.write(destination, source.collect(staging), appVersion, clock().toString())
        } finally {
            staging.deleteRecursively()
            log("backup.export", started, 0, "")
        }
    }

    fun preview(archive: File): BackupPreview = reader.preview(archive)

    suspend fun restore(archive: File): BackupPreview = operationMutex.withLock {
        val started = clock()
        val staging = File(appContext.filesDir, "restore-tmp-${UUID.randomUUID()}")
        var entryCount = 0
        var archiveHash = ""
        try {
            val extracted = reader.extract(archive, staging)
            entryCount = extracted.manifest.entryCount
            archiveHash = extracted.archiveSha256
            validator.validateDatabaseIfPresent(File(staging, BackupSource.CHAT_DATABASE_NAME))
            val replacements = mapRestoreTargets(extracted)
            RestoreTransaction().replaceFiles(replacements) {
                applySettings(File(staging, BackupSource.SETTINGS_BACKUP_FILE))
            }
            log("backup.restore", started, entryCount, archiveHash)
            preview(archive)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun mapRestoreTargets(extracted: ExtractedBackup): Map<File, File> {
        val mapping = linkedMapOf<File, File>()
        extracted.manifest.entries.forEach { entry ->
            val relative = entry.path.removePrefix("data/")
            val staged = File(extracted.stagingDirectory, relative)
            val destination = when {
                relative == BackupSource.CHAT_DATABASE_NAME ->
                    appContext.getDatabasePath(BackupSource.CHAT_DATABASE_NAME)
                relative.startsWith("memory/") ->
                    File(appContext.filesDir, relative.substringAfter("memory/"))
                relative.startsWith("worldbooks/") ->
                    File(appContext.filesDir, relative)
                relative.startsWith("characters/") ->
                    File(appContext.filesDir, relative)
                relative.startsWith("generated-images/") ->
                    File(appContext.filesDir, relative)
                relative == BackupSource.SETTINGS_BACKUP_FILE -> null
                else -> throw IllegalArgumentException("Backup contains an unsupported data path.")
            }
            if (destination != null) mapping[staged] = destination
        }
        return mapping
    }

    private fun applySettings(file: File) {
        if (!file.isFile) return
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val preferences = appContext.getSharedPreferences(BackupSource.SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.all
        val editor = preferences.edit().clear()
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.contains("credential", true) || key.contains("secret", true) ||
                key.contains("apikey", true) || key.contains("api_key", true) ||
                key.contains("token", true) || key.contains("keystore", true)
            ) continue
            when (val value = json.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is org.json.JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
            }
        }
        if (!editor.commit()) {
            val rollback = preferences.edit().clear()
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> rollback.putString(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Long -> rollback.putLong(key, value)
                    is Float -> rollback.putFloat(key, value)
                    is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            rollback.commit()
            throw IOException("Settings restore could not be committed.")
        }
    }

    private fun log(event: String, started: Instant, entries: Int, hash: String) {
        val elapsed = Duration.between(started, clock()).toMillis().coerceAtLeast(0)
        runCatching {
            File(appContext.filesDir, "mistbell-v3-backup-diagnostics.log").appendText(
                "${clock()} $event entries=$entries elapsedMs=$elapsed archiveSha256=$hash\n",
                Charsets.UTF_8,
            )
        }.onFailure { error ->
            Log.w(TAG, "Backup diagnostic event could not be persisted: ${error::class.java.simpleName}")
        }
    }

    companion object {
        private const val TAG = "MistbellBackup"
        private val operationMutex = Mutex()
    }
}

/** Android-specific DB verification kept behind a seam for JVM unit tests. */
class BackupValidatorFacade {
    fun validateDatabaseIfPresent(file: File) {
        if (!file.exists()) return
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Backup chat database failed integrity_check."
                }
            }
        } finally {
            database.close()
        }
    }
}
