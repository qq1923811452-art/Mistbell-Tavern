package com.mistbell.tavern.v3.feature.era

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.v3.BuildConfig
import java.io.File

/**
 * Persistent game library for the eraTW adapter.
 *
 * A selected ZIP is copied to cache, extracted, and then forgotten. The imported directory is the
 * unit of updates: importing a pack with the same name replaces its files while the installer
 * carries `sav/` and root `save*.sav` across.
 */
class EraGameRepository(context: Context) {
    private val appContext = context.applicationContext
    private val gamesRoot = File(appContext.filesDir, GAMES_DIRECTORY)
    private val installer = EraGameZipInstaller(gamesRoot)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun current(): EraGameInstall? = currentFromZip() ?: currentFromDirectory()

    private fun currentFromZip(): EraGameInstall? {
        val zipPath = preferences.getString(KEY_ZIP_PATH, null)?.let(::File)
        return if (zipPath != null && zipPath.isFile) {
            EraGameInstall(
                name = preferences.getString(KEY_DISPLAY_NAME, null) ?: zipPath.name,
                directory = zipPath.parentFile ?: zipPath,
                importedAtEpochMillis = preferences.getLong(KEY_IMPORTED_AT, 0L),
                fileCount = preferences.getInt(KEY_FILE_COUNT, 0),
                zipPath = zipPath,
            )
        } else {
            null
        }
    }

    private fun currentFromDirectory(): EraGameInstall? {
        val directoryName = preferences.getString(KEY_DIRECTORY_NAME, null) ?: return null
        val directory = File(gamesRoot, directoryName)
        return if (directory.isDirectory) {
            EraGameInstall(
                name = preferences.getString(KEY_DISPLAY_NAME, null) ?: directoryName,
                directory = directory,
                importedAtEpochMillis = preferences.getLong(KEY_IMPORTED_AT, 0L),
                fileCount = preferences.getInt(KEY_FILE_COUNT, 0),
            )
        } else {
            null
        }
    }

    fun importZip(
        uri: Uri,
        displayName: String,
    ): EraGameInstall {
        if (BuildConfig.ERA_MERGED) return importZipForMerged(uri, displayName)
        val stagingZip = File.createTempFile("era-import-", ".zip", appContext.cacheDir)
        try {
            val input =
                appContext.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选 ZIP 文件")
            input.use { source ->
                stagingZip.outputStream().buffered().use { destination ->
                    source.copyTo(destination)
                }
            }
            val install = installer.install(stagingZip, displayName)
            preferences.edit()
                .putString(KEY_DIRECTORY_NAME, install.directory.name)
                .putString(KEY_DISPLAY_NAME, install.name)
                .putLong(KEY_IMPORTED_AT, install.importedAtEpochMillis)
                .putInt(KEY_FILE_COUNT, install.fileCount)
                .apply()
            return install
        } finally {
            stagingZip.delete()
        }
    }

    /**
     * 单包：只在 cache 里留一份 ZIP，交给引擎解一次。
     * 之前 Kotlin 解到 files/era/games、引擎又解到 files/games，磁盘和导入时间都翻倍。
     */
    private fun importZipForMerged(
        uri: Uri,
        displayName: String,
    ): EraGameInstall {
        val safeName =
            displayName
                .replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5._-]"), "_")
                .take(MAX_NAME_LENGTH)
                .ifBlank { "era" }
        val sourceDir = File(appContext.cacheDir, "era-src").apply { mkdirs() }
        val zip = File(sourceDir, "$safeName.zip")
        val temp = File(sourceDir, "$safeName.zip.part")
        val input = appContext.contentResolver.openInputStream(uri) ?: error("无法读取所选 ZIP 文件")
        input.use { source -> temp.outputStream().buffered().use { source.copyTo(it) } }
        if (zip.exists()) zip.delete()
        if (!temp.renameTo(zip)) error("无法保存 ZIP 副本")
        val entryCount = runCatching { java.util.zip.ZipFile(zip).use { it.size() } }.getOrDefault(0)
        // 清掉旧版本解包出来的目录，避免两份 16k 文件同时占空间
        runCatching { File(appContext.filesDir, GAMES_DIRECTORY).deleteRecursively() }
        preferences.edit()
            .putString(KEY_ZIP_PATH, zip.absolutePath)
            .remove(KEY_DIRECTORY_NAME)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_IMPORTED_AT, System.currentTimeMillis())
            .putInt(KEY_FILE_COUNT, entryCount)
            .apply()
        return EraGameInstall(
            name = displayName,
            directory = sourceDir,
            importedAtEpochMillis = preferences.getLong(KEY_IMPORTED_AT, 0L),
            fileCount = entryCount,
            zipPath = zip,
        )
    }

    private companion object {
        const val GAMES_DIRECTORY = "era/games"
        const val PREFERENCES_NAME = "mistbell-era"
        const val KEY_DIRECTORY_NAME = "game_directory"
        const val KEY_DISPLAY_NAME = "game_display_name"
        const val KEY_IMPORTED_AT = "game_imported_at"
        const val KEY_FILE_COUNT = "game_file_count"
        const val KEY_ZIP_PATH = "game_zip_path"
        const val MAX_NAME_LENGTH = 48
    }
}
