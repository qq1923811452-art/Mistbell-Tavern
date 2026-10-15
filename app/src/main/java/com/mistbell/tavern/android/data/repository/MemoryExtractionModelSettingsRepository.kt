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
    val keyNeedsReentry: Boolean = false,
    val clearApiKey: Boolean = false,
) {
    /** Return a safe, user-facing reason when this endpoint must not be used. */
    fun endpointRejectionReason(): String? {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val endpoint = runCatching { URI(normalizedBaseUrl) }.getOrNull()
        val scheme = endpoint?.scheme?.lowercase()
        val host = endpoint?.host?.lowercase()
        val loopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")

        return when {
            normalizedBaseUrl.isBlank() -> "API 基础地址不能为空"
            endpoint == null -> "API 基础地址格式无效"
            scheme !in setOf("http", "https") || host.isNullOrBlank() ->
                "API 基础地址必须是有效的 HTTP 或 HTTPS URL"
            !endpoint.rawUserInfo.isNullOrBlank() -> "API 基础地址不能包含用户名或密码"
            scheme == "http" && !loopback ->
                "远程 API 必须使用 HTTPS；HTTP 仅允许本机回环地址"
            else -> null
        }
    }

    /** Remote endpoints require a key; a blank key is accepted only for loopback endpoints. */
    fun toLlmConfigOrNull(): LlmConfig? {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedModel = model.trim()
        val endpoint = runCatching { URI(normalizedBaseUrl) }.getOrNull()
        val host = endpoint?.host?.lowercase()
        val loopbackEndpoint = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        val validKey = !keyNeedsReentry && (apiKey.isNotBlank() || loopbackEndpoint)
        val validModelTarget = normalizedBaseUrl.isNotBlank() && normalizedModel.isNotBlank()
        return if (endpointRejectionReason() == null && validModelTarget && validKey) {
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
            database.withTransaction {
                val existingKey = settingsDao.getValue(KEY_API_KEY).orEmpty()
                val unreadableCiphertext =
                    existingKey.startsWith(SecureStore.PREFIX) && !SecureStore.isEncrypted(existingKey)
                check(!(apiKey.isBlank() && unreadableCiphertext && !settings.clearApiKey)) {
                    "已保存的记忆模型 API Key 无法解密；请重新输入 API Key 或明确清除该 Key。原配置未修改。"
                }
                // A blank field means leave the existing key unchanged; clearing it requires an explicit action.
                val encryptedApiKey =
                    when {
                        settings.clearApiKey -> ""
                        apiKey.isNotBlank() -> SecureStore.wrap(apiKey)
                        existingKey.isNotBlank() && existingKey.startsWith(SecureStore.PREFIX) -> existingKey
                        existingKey.isNotBlank() -> SecureStore.wrap(existingKey)
                        else -> ""
                    }
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