package com.mistbell.tavern.android.data.repository

import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.util.SecureStore
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent provider settings for background memory extraction; stored in the existing KV table. */
data class MemoryExtractionModelSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val keyNeedsReentry: Boolean = false,
) {
    /** Remote endpoints require a key; a blank key is accepted only for loopback endpoints. */
    fun toLlmConfigOrNull(): LlmConfig? {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedModel = model.trim()
        val endpoint = runCatching { URI(normalizedBaseUrl) }.getOrNull()
        val host = endpoint?.host?.lowercase()
        val validEndpoint =
            endpoint?.let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() } == true
        val loopbackEndpoint = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        val validKey = !keyNeedsReentry && (apiKey.isNotBlank() || loopbackEndpoint)
        val validModelTarget = normalizedBaseUrl.isNotBlank() && normalizedModel.isNotBlank()
        return if (validModelTarget && validEndpoint && validKey) {
            LlmConfig(
                baseUrl = normalizedBaseUrl,
                apiKey = apiKey.trim(),
                model = normalizedModel,
            )
        } else {
            null
        }
    }
}

class MemoryExtractionModelSettingsRepository {
    private val database get() = TavernApplication.instance.container.database
    private val settingsDao get() = database.settingsDao()

    suspend fun load(): MemoryExtractionModelSettings =
        withContext(Dispatchers.IO) {
            // Read the three values from one Room snapshot so a concurrent save cannot mix versions.
            val values =
                database.withTransaction {
                    Triple(
                        settingsDao.getValue(KEY_BASE_URL).orEmpty(),
                        settingsDao.getValue(KEY_API_KEY).orEmpty(),
                        settingsDao.getValue(KEY_MODEL).orEmpty(),
                    )
                }
            val storedApiKey = values.second
            val decryptedApiKey =
                if (storedApiKey.isBlank()) {
                    ""
                } else {
                    try {
                        SecureStore.unwrap(storedApiKey)
                    } catch (_: Exception) {
                        android.util.Log.w(
                            TAG,
                            "Stored independent memory API key could not be decrypted; re-entry is required",
                        )
                        ""
                    }
                }
            val keyNeedsReentry = storedApiKey.isNotBlank() && decryptedApiKey.isBlank()
            MemoryExtractionModelSettings(
                baseUrl = values.first,
                apiKey = decryptedApiKey,
                model = values.third,
                keyNeedsReentry = keyNeedsReentry,
            )
        }

    suspend fun save(settings: MemoryExtractionModelSettings) {
        withContext(Dispatchers.IO) {
            val apiKey = settings.apiKey.trim()
            val encryptedApiKey = if (apiKey.isBlank()) "" else SecureStore.wrap(apiKey)
            database.withTransaction {
                settingsDao.upsert(SettingsEntity(KEY_BASE_URL, settings.baseUrl.trim()))
                settingsDao.upsert(SettingsEntity(KEY_API_KEY, encryptedApiKey))
                settingsDao.upsert(SettingsEntity(KEY_MODEL, settings.model.trim()))
            }
        }
    }

    suspend fun resolveLlmConfigOrNull(): LlmConfig? = load().toLlmConfigOrNull()

    companion object {
        private const val TAG = "MemoryModelSettings"
        const val KEY_BASE_URL = "memory_llm_base_url"
        const val KEY_API_KEY = "memory_llm_api_key"
        const val KEY_MODEL = "memory_llm_model"
    }
}
