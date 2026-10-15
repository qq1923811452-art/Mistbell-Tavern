package com.mistbell.tavern.android.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.util.ApiEndpointPolicy
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 旧版提供商配置的遗留数据格式（providers_json 的序列化结构）。
 *
 * 仅用于 [ApiConfigMigration] 解析老用户数据；新代码一律使用 ApiConfigEntity / api_configs 表。
 * 键名（apiKey/selectedModel/top_p 等）必须与历史落盘的 JSON 完全一致，不得改名。
 */
@Serializable
internal data class ProviderConfig(
    val id: String = "",
    val name: String = "",
    val type: String = "openai",
    val endpoint: String = "",
    @SerialName("apiKey") val apiKey: String = "",
    val models: List<String> = emptyList(),
    @SerialName("selectedModel") val selectedModel: String = "",
    @SerialName("embeddingModel") val embeddingModel: String = "",
    @SerialName("summaryModel") val summaryModel: String = "",
    @SerialName("memoryModel") val memoryModel: String = "",
    @SerialName("customParams") val customParams: Map<String, String> = emptyMap(),
    @SerialName("context1M") val context1M: Boolean = false,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

/**
 * 旧提供商链路（settings KV `providers_json`）→ 新 api_configs 表的一次性数据迁移。
 *
 * 背景：卡片式 API 配置（api_configs 表）成为唯一配置入口后，老用户存在 providers_json
 * 里的配置必须搬过来，否则升级后"配置还在旧格式、新界面读不到"。
 *
 * 语义要点：
 * - **保留原 ProviderConfig.id 作为 ApiConfigEntity.id**：存量 session.providerId 引用不断链；
 * - 迁移在 ApiConfigViewModel.init（应用启动后首次触达配置页）执行，幂等（标志键
 *   [MIGRATED_FLAG_KEY]，且 api_configs 非空即跳过，恢复备份场景同样安全）；
 * - 激活提供商的高级采样参数继续平铺写入 llm_* 覆盖键（getLlmConfig 后半段机制不变）。
 */
object ApiConfigMigration {
    private const val TAG = "ApiConfigMigration"
    const val MIGRATED_FLAG_KEY = "providers_migrated_v1"

    /** 纯函数：解密后的 providers_json → ProviderConfig 列表；空串合法，非空坏 JSON 必须失败。 */
    internal fun parseProvidersJson(json: String): List<ProviderConfig> {
        if (json.isBlank()) return emptyList()
        return Json.decodeFromString(ListSerializer(ProviderConfig.serializer()), json)
    }

    /** 纯函数：ProviderConfig 列表 → ApiConfigEntity 列表（id 保留；activeId 为默认，缺省取第一个） */
    internal fun toApiConfigEntities(
        providers: List<ProviderConfig>,
        activeId: String,
        now: Long,
    ): List<ApiConfigEntity> =
        providers.mapIndexed { index, provider ->
            ApiConfigEntity(
                id = provider.id.ifBlank { UUID.randomUUID().toString() },
                name = provider.name,
                apiUrl = provider.endpoint,
                apiKey = provider.apiKey,
                model = provider.selectedModel,
                isDefault = provider.id == activeId || (activeId.isBlank() && index == 0),
                sortOrder = index,
                type = provider.type.ifBlank { "openai" },
                context1m = provider.context1M,
                streamingEnabled = true,
                createdAt = now,
                updatedAt = now,
            )
        }

    /**
     * Legacy endpoints are sanitized rather than aborting the whole migration. Invalid endpoints
     * become blank (and therefore unusable) until the user reconfigures them; the encrypted
     * providers_json source is retained so the original value is not destroyed.
     */
    internal fun endpointForMigration(endpoint: String): String =
        if (ApiEndpointPolicy.writeRejectionReason(endpoint) == null) endpoint else ""

    /** 纯函数：激活提供商的高级采样参数 → llm_* 平铺覆盖键（null = 空串清除覆盖） */
    internal fun activeProviderOverrides(active: ProviderConfig): List<Pair<String, String>> =
        listOf(
            "llm_temperature" to (active.temperature?.toString() ?: ""),
            "llm_top_p" to (active.topP?.toString() ?: ""),
            "llm_top_k" to (active.topK?.toString() ?: ""),
            "llm_frequency_penalty" to (active.frequencyPenalty?.toString() ?: ""),
            "llm_max_tokens" to (active.maxTokens?.toString() ?: ""),
        )

    /**
     * 幂等迁移入口：已迁移 / api_configs 已有数据 / 无 providers_json 都直接置标志返回。
     */
    suspend fun migrateIfNeeded(db: AppDatabase) {
        db.withTransaction { migrateInTransaction(db) }
    }

    private suspend fun migrateInTransaction(db: AppDatabase) {
        encryptLegacyApiKeys(db)
        val settingsDao = db.settingsDao()
        val alreadyMigrated =
            settingsDao.getValue(MIGRATED_FLAG_KEY) == "1" || db.apiConfigDao().getCount() > 0

        encryptLegacySettings(settingsDao, alreadyMigrated)
        if (alreadyMigrated) {
            settingsDao.upsert(SettingsEntity(MIGRATED_FLAG_KEY, "1"))
            return
        }

        migrateProviders(db, settingsDao)
    }

    private suspend fun encryptLegacySettings(
        settingsDao: com.mistbell.tavern.android.data.local.dao.SettingsDao,
        alreadyMigrated: Boolean,
    ) {
        listOf("providers_json", "llm_api_key").forEach { key ->
            val value = settingsDao.getValue(key).orEmpty()
            if (value.isBlank()) return@forEach
            val encrypted = SecureStore.isEncrypted(value)
            val unreadableCiphertext = value.startsWith(SecureStore.PREFIX) && !encrypted
            if (unreadableCiphertext && key == "providers_json" && alreadyMigrated) return@forEach
            check(!unreadableCiphertext) {
                "设置 $key 的加密数据不可读取，迁移已中止以保留原始数据"
            }
            if (!encrypted) settingsDao.upsert(SettingsEntity(key, SecureStore.wrap(value)))
        }
    }

    private suspend fun migrateProviders(
        db: AppDatabase,
        settingsDao: com.mistbell.tavern.android.data.local.dao.SettingsDao,
    ) {
        val storedProvidersJson = settingsDao.getValue("providers_json").orEmpty()
        val decodedProvidersJson = SecureStore.unwrap(storedProvidersJson)
        check(!storedProvidersJson.startsWith(SecureStore.PREFIX) || decodedProvidersJson.isNotEmpty()) {
            "无法解密旧版 API 配置，迁移已中止以保留原始数据"
        }
        val providers = parseProvidersJson(decodedProvidersJson)
        if (providers.isEmpty()) {
            settingsDao.upsert(SettingsEntity(MIGRATED_FLAG_KEY, "1"))
            return
        }

        val activeId = settingsDao.getValue("active_provider_id") ?: ""
        val now = System.currentTimeMillis()
        val entities =
            toApiConfigEntities(providers, activeId, now).map { entity ->
                val safeEndpoint = endpointForMigration(entity.apiUrl)
                if (safeEndpoint.isEmpty()) {
                    // Do not abort migration of every provider because one legacy endpoint is unsafe.
                    // The original providers_json remains encrypted for recovery; the migrated entry
                    // is left unconfigured so it cannot make a request until the user fixes its URL.
                    Log.w(TAG, "Legacy API config ${entity.id} requires endpoint reconfiguration after migration")
                }
                entity.copy(apiUrl = safeEndpoint, apiKey = SecureStore.wrap(entity.apiKey))
            }
        db.apiConfigDao().insertAll(entities)

        val active = providers.firstOrNull { it.id == activeId } ?: providers.first()
        activeProviderOverrides(active).forEach { (key, value) ->
            settingsDao.upsert(SettingsEntity(key, value))
        }
        settingsDao.upsert(SettingsEntity(MIGRATED_FLAG_KEY, "1"))
    }

    /**
     * Encrypt API keys created by earlier versions. Encryption errors deliberately propagate:
     * silently retaining plaintext would violate the storage security guarantee.
     */
    private suspend fun encryptLegacyApiKeys(db: AppDatabase) {
        db.apiConfigDao().getAll().forEach { entity ->
            if (entity.apiKey.isNotBlank()) {
                val encrypted = SecureStore.isEncrypted(entity.apiKey)
                check(!entity.apiKey.startsWith(SecureStore.PREFIX) || encrypted) {
                    "API 配置 ${entity.id} 的加密数据不可读取，迁移已中止以保留原始数据"
                }
                if (!encrypted) {
                    db.apiConfigDao().update(entity.copy(apiKey = SecureStore.wrap(entity.apiKey)))
                }
            }
        }
    }
}
