package com.mistbell.tavern.android.data.repository

import androidx.room.withTransaction
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** Independent provider settings for background memory extraction; stored in the existing KV table. */
data class MemoryExtractionModelSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** A dedicated model is active only when its endpoint and model are explicitly configured. */
    fun toLlmConfigOrNull(): LlmConfig? {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedModel = model.trim()
        val endpoint = runCatching { URI(normalizedBaseUrl) }.getOrNull()
        val validEndpoint =
            endpoint != null &&
                endpoint.scheme in setOf("http", "https") &&
                !endpoint.host.isNullOrBlank()
        return if (normalizedBaseUrl.isNotBlank() && normalizedModel.isNotBlank() && validEndpoint) {
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
            val values =
                database.withTransaction {
                    Triple(
                        settingsDao.getValue(KEY_BASE_URL).orEmpty(),
                        settingsDao.getValue(KEY_API_KEY).orEmpty(),
                        settingsDao.getValue(KEY_MODEL).orEmpty(),
                    )
                }
            MemoryExtractionModelSettings(
                baseUrl = values.first,
                apiKey = SecureStore.unwrap(values.second),
                model = values.third,
            )
        }

    suspend fun save(settings: MemoryExtractionModelSettings) {
        withContext(Dispatchers.IO) {
            val encryptedApiKey = SecureStore.wrap(settings.apiKey.trim())
            database.withTransaction {
                settingsDao.upsert(SettingsEntity(KEY_BASE_URL, settings.baseUrl.trim()))
                settingsDao.upsert(SettingsEntity(KEY_API_KEY, encryptedApiKey))
                settingsDao.upsert(SettingsEntity(KEY_MODEL, settings.model.trim()))
            }
        }
    }

    suspend fun resolveLlmConfigOrNull(): LlmConfig? = load().toLlmConfigOrNull()

    companion object {
        const val KEY_BASE_URL = "memory_llm_base_url"
        const val KEY_API_KEY = "memory_llm_api_key"
        const val KEY_MODEL = "memory_llm_model"
    }
}
