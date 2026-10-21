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
@Suppress("LongParameterList")
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
        var result: BackupArchiveResult? = null
        try {
            require(staging.mkdirs()) { "Backup temporary directory could not be created." }
            writer.write(destination, source.collect(staging), appVersion, clock().toString())
                .also { result = it }
        } finally {
            staging.deleteRecursively()
            result?.let { log("backup.export", started, it.manifest.entryCount, it.archiveSha256) }
        }
    }

    fun preview(archive: File): BackupPreview = reader.preview(archive)

    @Suppress("TooGenericExceptionCaught")
    suspend fun restore(archive: File): BackupPreview = operationMutex.withLock {
        val started = clock()
        val staging = File(appContext.filesDir, "restore-tmp-${UUID.randomUUID()}")
        var entryCount = 0
        var archiveHash = ""
        try {
            val extracted = reader.extract(archive, staging)
            entryCount = extracted.manifest.entryCount
            archiveHash = extracted.archiveSha256
            validateStagedDatabase(File(staging, BackupSource.CHAT_DATABASE_NAME))
            validateStagedDatabase(File(staging, BackupSource.SESSION_DATABASE_NAME))
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
                    beforeRollback = {
                        databaseLifecycle.closeBeforeRestore()
                        rollbackSettings?.invoke()
                        rollbackSettings = null
                    },
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
            BackupPreview(extracted.manifest, extracted.archiveBytes, archiveHash)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun validateStagedDatabase(stagedDatabase: File) {
        if (!stagedDatabase.isFile) return
        val validationDirectory = File(appContext.cacheDir, "backup-db-validation-" + UUID.randomUUID())
        require(validationDirectory.mkdirs()) { "Database validation directory could not be created." }
        try {
            val isolatedCopy = File(validationDirectory, BackupSource.CHAT_DATABASE_NAME)
            stagedDatabase.copyTo(isolatedCopy, overwrite = true)
            validator.validateDatabaseIfPresent(isolatedCopy)
        } finally {
            validationDirectory.deleteRecursively()
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
            // Older or partial archives may not contain every optional directory. Do not
            // replace a user's existing directory with an empty staging directory in that case.
            if (archivePaths.any { path -> path.startsWith("$name/") }) {
                val stagedDirectory = File(extracted.stagingDirectory, name)
                if (!stagedDirectory.exists() && !stagedDirectory.mkdirs()) {
                    throw IOException("Restore staging directory could not be created.")
                }
                replacements[stagedDirectory] = File(appContext.filesDir, name)
            }
        }

        listOf(BackupSource.CHAT_DATABASE_NAME, BackupSource.SESSION_DATABASE_NAME).forEach { name ->
            val databaseFile = appContext.getDatabasePath(name)
            val stagedDatabase = File(extracted.stagingDirectory, name)
            if (archivePaths.contains(name)) {
                replacements[stagedDatabase] = databaseFile
            } else {
                removals += databaseFile
            }
            // A WAL or SHM from the previous database must never be replayed against the restored DB.
            removals += File(databaseFile.path + "-wal")
            removals += File(databaseFile.path + "-shm")
        }

        return RestorePlan(replacements, removals)
    }

    private fun applySettings(file: File): () -> Unit {
        if (!file.isFile) return {}
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val preferences = appContext.getSharedPreferences(BackupSource.SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.all
        val editor = preferences.edit().clear()
        // Credentials are deliberately absent from the archive; preserve the device's current
        // credentials instead of clearing them as a side effect of restoring ordinary settings.
        previous.filterKeys(::isCredentialKey).forEach { (key, value) ->
            putPreferenceValue(editor, key, value)
        }
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (isCredentialKey(key)) continue
            when (val value = json.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is org.json.JSONArray -> {
                    val values = (0 until value.length()).map { value.getString(it) }.toSet()
                    editor.putStringSet(key, values)
                }
            }
        }
        if (!editor.commit()) {
            val commitFailure = IOException("Settings restore could not be committed.")
            runCatching { restorePreferences(preferences, previous) }
                .onFailure(commitFailure::addSuppressed)
            throw commitFailure
        }
        return { restorePreferences(preferences, previous) }
    }

    private fun putPreferenceValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Any?,
    ) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun isCredentialKey(key: String): Boolean {
        val sensitiveParts = listOf(
            "credential", "secret", "apikey", "api_key", "api-key", "password",
            "token", "keystore", "privatekey", "private_key",
        )
        return sensitiveParts.any { part -> key.contains(part, ignoreCase = true) }
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
