package com.mistbell.tavern.v3.feature.era

import android.content.Context
import android.net.Uri
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

    fun current(): EraGameInstall? {
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

    private companion object {
        const val GAMES_DIRECTORY = "era/games"
        const val PREFERENCES_NAME = "mistbell-era"
        const val KEY_DIRECTORY_NAME = "game_directory"
        const val KEY_DISPLAY_NAME = "game_display_name"
        const val KEY_IMPORTED_AT = "game_imported_at"
        const val KEY_FILE_COUNT = "game_file_count"
    }
}
