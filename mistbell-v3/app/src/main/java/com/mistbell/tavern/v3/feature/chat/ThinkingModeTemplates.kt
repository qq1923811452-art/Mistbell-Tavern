package com.mistbell.tavern.v3.feature.chat

/**
 * Known provider request fragments for enabling and disabling reasoning.
 *
 * Providers without a known convention return null; the toggle then leaves the request
 * unchanged instead of sending a field the endpoint may reject.
 */
internal object ThinkingModeTemplates {
    fun enabled(baseUrl: String, modelId: String): String? =
        select(baseUrl, modelId, enabled = true)

    fun disabled(baseUrl: String, modelId: String): String? =
        select(baseUrl, modelId, enabled = false)

    @Suppress("CyclomaticComplexMethod")
    private fun select(baseUrl: String, modelId: String, enabled: Boolean): String? {
        val url = baseUrl.lowercase()
        val model = modelId.lowercase()
        val type = if (enabled) "enabled" else "disabled"
        return when {
            // DeepSeek OpenAI-format API: {"thinking":{"type":"enabled"|"disabled"}}.
            url.contains("deepseek") || model.contains("deepseek") ->
                "{\"thinking\":{\"type\":\"$type\"}}"
            url.contains("dashscope") || url.contains("aliyuncs") ->
                "{\"enable_thinking\":$enabled}"
            url.contains("openrouter") ->
                "{\"reasoning\":{\"enabled\":$enabled}}"
            url.contains("volces") || url.contains("volcengine") || url.contains("ark.cn-beijing") ->
                "{\"thinking\":{\"type\":\"$type\"}}"
            url.contains("bigmodel.cn") ->
                "{\"thinking\":{\"type\":\"$type\"}}"
            url.contains("siliconflow") -> "{\"enable_thinking\":$enabled}"
            // Anthropic and Gemini use different protocols/versions; do not guess here.
            else -> null
        }
    }
}
