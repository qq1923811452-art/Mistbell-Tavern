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

/**
 * Implemented by the app integration layer to close every live chat database handle before the
 * swap and reopen the database after installation or rollback. Replacing an open SQLite file is unsafe.
 */
interface BackupDatabaseLifecycle {
    fun closeBeforeRestore()
    fun reopenAfterRestore()
}

/** Export, preview and restore orchestration. No UI entry points are added in this change. */
class BackupService(
    context: Context,
    private val appVersion: String = "3.0.0-dev",
    private val clock: () -> Instant = Instant::now,
    private val writer: BackupArchiveWriter = BackupArchiveWriter(),
    private val reader: BackupArchiveReader = BackupArchiveReader(),
    private val validator: BackupValidatorFacade = BackupValidatorFacade(),
    private val databaseLifecycle: BackupDatabaseLifecycle,
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
            val plan = mapRestoreTargets(extracted)
            var rollbackSettings: (() -> Unit)? = null
            var rollbackLifecycleHandled = false
            databaseLifecycle.closeBeforeRestore()
            try {
                RestoreTransaction().replaceFiles(
                    stagedToDestination = plan.replacements,
                    removeDestinations = plan.removals,
                    beforeCommit = {
                        rollbackSettings = applySettings(File(staging, BackupSource.SETTINGS_BACKUP_FILE))
                        databaseLifecycle.reopenAfterRestore()
                    },
                    beforeRollback = { databaseLifecycle.closeBeforeRestore() },
                    afterRollback = {
                        databaseLifecycle.reopenAfterRestore()
                        rollbackLifecycleHandled = true
                    },
                )
            } catch (failure: Throwable) {
                runCatching { rollbackSettings?.invoke() }.onFailure(failure::addSuppressed)
                if (!rollbackLifecycleHandled) {
                    runCatching { databaseLifecycle.reopenAfterRestore() }.onFailure(failure::addSuppressed)
                }
                throw failure
            }
            log("backup.restore", started, entryCount, archiveHash)
            preview(archive)
        } finally {
            staging.deleteRecursively()
        }
    }

    private data class RestorePlan(
        val replacements: Map<File, File>,
        val removals: Set<File>,
    )

    private fun mapRestoreTargets(extracted: ExtractedBackup): RestorePlan {
        val replacements = linkedMapOf<File, File>()
        val removals = linkedSetOf<File>()
        val archivePaths = extracted.manifest.entries.map { it.path.removePrefix("data/") }.toSet()

        archivePaths.filter { it.startsWith("memory/") }.forEach { relative ->
            val name = relative.substringAfter("memory/")
            val staged = File(extracted.stagingDirectory, relative)
            replacements[staged] = File(appContext.filesDir, name)
        }
        appContext.filesDir.listFiles().orEmpty()
            .filter { it.isFile && BackupSource.isMemoryFileName(it.name) }
            .filterNot { it.name in archivePaths.map { path -> path.substringAfter("memory/") } }
            .forEach { removals += it }

        listOf("worldbooks", "characters", "generated-images").forEach { name ->
            val stagedDirectory = File(extracted.stagingDirectory, name)
            if (!stagedDirectory.exists() && !stagedDirectory.mkdirs()) {
                throw IOException("Restore staging directory could not be created.")
            }
            replacements[stagedDirectory] = File(appContext.filesDir, name)
        }

        val databaseFile = appContext.getDatabasePath(BackupSource.CHAT_DATABASE_NAME)
        val stagedDatabase = File(extracted.stagingDirectory, BackupSource.CHAT_DATABASE_NAME)
        if (archivePaths.contains(BackupSource.CHAT_DATABASE_NAME)) {
            replacements[stagedDatabase] = databaseFile
        } else {
            removals += databaseFile
        }
        // A WAL or SHM from the previous database must never be replayed against the restored DB.
        removals += File(databaseFile.path + "-wal")
        removals += File(databaseFile.path + "-shm")

        return RestorePlan(replacements, removals)
    }

    private fun applySettings(file: File): () -> Unit {
        if (!file.isFile) return {}
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
            restorePreferences(preferences, previous)
            throw IOException("Settings restore could not be committed.")
        }
        return { restorePreferences(preferences, previous) }
    }

    private fun restorePreferences(
        preferences: android.content.SharedPreferences,
        values: Map<String, *>,
    ) {
        val rollback = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> rollback.putString(key, value)
                is Boolean -> rollback.putBoolean(key, value)
                is Int -> rollback.putInt(key, value)
                is Long -> rollback.putLong(key, value)
                is Float -> rollback.putFloat(key, value)
                is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (!rollback.commit()) throw IOException("Previous settings could not be restored.")
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
