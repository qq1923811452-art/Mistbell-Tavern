package com.mistbell.tavern.android.data.repository

import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.api.SamplerPresets
import com.mistbell.tavern.android.data.api.SamplingParams
import com.mistbell.tavern.android.data.local.dao.SettingsDao
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class SettingsRepository {
    private val db get() = TavernApplication.instance.container.database

    // --- LLM Config (local) ---

    suspend fun getLlmConfig(): LlmConfig =
        withContext(Dispatchers.IO) {
            val dao = db.settingsDao()
            // S1: 采样预设兜底（缺省 balanced）
            val preset = SamplerPresets.byName(dao.getValue("sampling_preset") ?: "balanced")
            // 三元组真相源：api_configs 默认配置（卡片式配置页写入）；表为空时回退
            // llm_* 平铺键（服务器同步 loadAndCacheSettings 仍写这组键）
            val base = buildBaseConfig(db.apiConfigDao().getDefault(), dao, preset)
            // S1: 最后过一遍预设解析，保证字段兜底逻辑一致
            SamplerPresets.resolve(base, preset)
        }

    // 基础 LlmConfig 组装：优先取 api_configs 默认配置的 url/key/model，缺省回退 llm_* 平铺键
    private suspend fun buildBaseConfig(
        defaultApiConfig: com.mistbell.tavern.android.data.local.entity.ApiConfigEntity?,
        dao: SettingsDao,
        preset: SamplingParams?,
    ): LlmConfig {
        // 提供商保存时平铺写入的 llm_* 覆盖键（空白=未设）
        val llmTemp = dao.doubleValue("llm_temperature")
        val llmTopP = dao.doubleValue("llm_top_p")
        val llmTopK = dao.intValue("llm_top_k")
        val llmFreqPenalty = dao.doubleValue("llm_frequency_penalty")
        val llmMaxTokens = dao.intValue("llm_max_tokens")
        // 兼容旧键：历史安装可能仍存 temperature / max_tokens（写入入口已移除），
        // 作为 llm_* 覆盖键未设时的兜底，保证旧值对聊天同样生效
        val legacyTemp = dao.doubleValue("temperature")
        val legacyMaxTokens = dao.intValue("max_tokens")
        val globalStreamingEnabled = dao.getValue("streaming_enabled") != "0"
        return LlmConfig(
            baseUrl = defaultApiConfig?.apiUrl ?: dao.getValue("llm_base_url") ?: "",
            apiKey =
                defaultApiConfig?.apiKey?.let(SecureStore::unwrap)
                    ?: SecureStore.unwrap(dao.getValue("llm_api_key") ?: ""),
            model = defaultApiConfig?.model ?: dao.getValue("llm_model") ?: "",
            type = defaultApiConfig?.type ?: "openai",
            temperature = resolveTemperature(llmTemp, legacyTemp, preset),
            maxTokens = llmMaxTokens ?: legacyMaxTokens ?: 1024,
            topP = llmTopP ?: preset?.topP,
            topK = llmTopK ?: preset?.topK,
            frequencyPenalty = llmFreqPenalty ?: preset?.frequencyPenalty,
            timeoutSeconds = resolveTimeoutSeconds(dao),
            retries = resolveRetries(dao),
            streamingEnabled = defaultApiConfig?.streamingEnabled ?: globalStreamingEnabled,
        )
    }

    private suspend fun resolveTimeoutSeconds(dao: SettingsDao): Int =
        (dao.getValue("request_timeout_seconds")?.toIntOrNull() ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)

    private suspend fun resolveRetries(dao: SettingsDao): Int =
        (dao.getValue("request_retries")?.toIntOrNull() ?: DEFAULT_RETRIES).coerceIn(0, MAX_RETRIES)

    // --- 生成与记忆默认值（settings KV）---

    // 流式输出开关：缺省视为开启（仅显式写 "0" 才关闭）
    suspend fun isStreamingEnabled(): Boolean =
        withContext(Dispatchers.IO) {
            db.settingsDao().getValue("streaming_enabled") != "0"
        }

    // 新会话默认上下文 token 预算：非法或缺省回退 4096（解析实现收敛到 ChatSettingsResolver）
    suspend fun defaultContextTokens(): Int =
        withContext(Dispatchers.IO) {
            ChatSettingsResolver.globalDefaultContextTokens(db.settingsDao().getValue("default_context_tokens"))
        }

    // 新会话默认长期记忆开关：缺省关闭（仅显式写 "1" 才开启）
    suspend fun defaultLtmEnabled(): Boolean =
        withContext(Dispatchers.IO) {
            ChatSettingsResolver.globalDefaultLtmEnabled(db.settingsDao().getValue("default_ltm_enabled"))
        }

    // --- Server settings (sync from API) ---

    fun observeSettings(): Flow<JsonObject?> {
        return db.settingsDao().getAll().map { entities ->
            val map = entities.associate { it.key to it.value }
            buildJsonObject {
                map.forEach { (k, v) ->
                    put(k, JsonPrimitive(v))
                }
            }
        }
    }

    // 已删除 loadAndCacheSettings()：它会向硬编码的明文地址 GET /api/settings，
    // 并把响应逐键写入本地设置表（见安全审查报告）。该链路属历史服务端架构遗留，
    // 真机上必然失败且构成「任意设置注入」面，应用纯本地后不再需要。
}

// 文件级私有工具：getLlmConfig 的键值解析与优先级兜底（置类外避免触发 TooManyFunctions，
// 同时把 elvis 链移出 getLlmConfig 压低圈复杂度）

// 请求策略默认值与钳制区间（与历史行为一致：90s/2 次，15..600s、0..5 次）
private const val DEFAULT_TIMEOUT_SECONDS = 90
private const val MIN_TIMEOUT_SECONDS = 15
private const val MAX_TIMEOUT_SECONDS = 600
private const val DEFAULT_RETRIES = 2
private const val MAX_RETRIES = 5

// 空白/非法值一律视为未设（null），由调用方按优先级兜底
private suspend fun SettingsDao.doubleValue(key: String): Double? = getValue(key)?.trim()?.toDoubleOrNull()

private suspend fun SettingsDao.intValue(key: String): Int? = getValue(key)?.trim()?.toIntOrNull()

// 温度优先级：提供商 llm_* 覆盖 > 旧键（历史安装残留）> 采样预设 > 默认 0.8
private const val DEFAULT_TEMPERATURE = 0.8

private fun resolveTemperature(
    override: Double?,
    legacy: Double?,
    preset: SamplingParams?,
): Double = override ?: legacy ?: preset?.temperature ?: DEFAULT_TEMPERATURE
