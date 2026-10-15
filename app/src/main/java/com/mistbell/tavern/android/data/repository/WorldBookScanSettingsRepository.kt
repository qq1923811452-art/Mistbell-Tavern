package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Global World Info recursion controls stored in the existing settings KV table. */
class WorldBookScanSettingsRepository {
    private val db get() = TavernApplication.instance.container.database

    fun observeEnabled(): Flow<Boolean> =
        db.settingsDao().observeValue(KEY_RECURSIVE_SCAN_ENABLED).map { it != "0" }

    fun observeDepth(): Flow<Int> =
        db.settingsDao().observeValue(KEY_RECURSIVE_SCAN_DEPTH).map {
            it?.toIntOrNull()?.coerceIn(MIN_RECURSION_DEPTH, MAX_RECURSION_DEPTH)
                ?: DEFAULT_RECURSION_DEPTH
        }

    suspend fun setEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(SettingsEntity(KEY_RECURSIVE_SCAN_ENABLED, if (enabled) "1" else "0"))
        }
    }

    suspend fun setDepth(depth: Int) {
        withContext(Dispatchers.IO) {
            db.settingsDao().upsert(
                SettingsEntity(
                    KEY_RECURSIVE_SCAN_DEPTH,
                    depth.coerceIn(MIN_RECURSION_DEPTH, MAX_RECURSION_DEPTH).toString(),
                ),
            )
        }
    }

    companion object {
        const val KEY_RECURSIVE_SCAN_ENABLED = "worldbook_recursive_scan_enabled"
        const val KEY_RECURSIVE_SCAN_DEPTH = "worldbook_recursive_scan_depth"
        const val MIN_RECURSION_DEPTH = 1
        const val MAX_RECURSION_DEPTH = 5
        const val DEFAULT_RECURSION_DEPTH = 3
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
