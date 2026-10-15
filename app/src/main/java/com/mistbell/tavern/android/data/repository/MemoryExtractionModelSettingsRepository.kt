package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val validEndpoint =
            normalizedBaseUrl.startsWith("https://") || normalizedBaseUrl.startsWith("http://")
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
    private val settingsDao get() = TavernApplication.instance.container.database.settingsDao()

    suspend fun load(): MemoryExtractionModelSettings =
        withContext(Dispatchers.IO) {
            MemoryExtractionModelSettings(
                baseUrl = settingsDao.getValue(KEY_BASE_URL).orEmpty(),
                apiKey = SecureStore.unwrap(settingsDao.getValue(KEY_API_KEY).orEmpty()),
                model = settingsDao.getValue(KEY_MODEL).orEmpty(),
            )
        }

    suspend fun save(settings: MemoryExtractionModelSettings) {
        withContext(Dispatchers.IO) {
            settingsDao.upsert(SettingsEntity(KEY_BASE_URL, settings.baseUrl.trim()))
            settingsDao.upsert(SettingsEntity(KEY_API_KEY, SecureStore.wrap(settings.apiKey.trim())))
            settingsDao.upsert(SettingsEntity(KEY_MODEL, settings.model.trim()))
        }
    }

    suspend fun resolveLlmConfigOrNull(): LlmConfig? = load().toLlmConfigOrNull()

    companion object {
        const val KEY_BASE_URL = "memory_llm_base_url"
        const val KEY_API_KEY = "memory_llm_api_key"
        const val KEY_MODEL = "memory_llm_model"
    }
}
