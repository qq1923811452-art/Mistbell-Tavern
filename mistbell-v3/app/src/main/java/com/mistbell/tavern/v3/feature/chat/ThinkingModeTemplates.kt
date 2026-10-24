package com.mistbell.tavern.v3.feature.chat

/**
 * Known provider request fragments for enabling and disabling reasoning.
 *
 * Providers without a known convention return null; the toggle then leaves the request
 * unchanged instead of sending a field the endpoint may reject.
 */
internal object ThinkingModeTemplates {
    fun enabled(baseUrl: String): String? = select(baseUrl, enabled = true)

    fun disabled(baseUrl: String): String? = select(baseUrl, enabled = false)

    private fun select(baseUrl: String, enabled: Boolean): String? {
        val url = baseUrl.lowercase()
        return when {
            url.contains("dashscope") || url.contains("aliyuncs") ->
                "{\"enable_thinking\":$enabled}"
            url.contains("openrouter") ->
                "{\"reasoning\":{\"enabled\":$enabled}}"
            url.contains("anthropic") -> if (enabled) {
                "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024}}"
            } else {
                "{\"thinking\":{\"type\":\"disabled\"}}"
            }
            url.contains("generativelanguage.googleapis") ->
                "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":${if (enabled) -1 else 0}}}}"
            url.contains("volces") || url.contains("volcengine") || url.contains("ark.cn-beijing") ->
                "{\"thinking\":{\"type\":\"${if (enabled) "enabled" else "disabled"}\"}}"
            url.contains("bigmodel.cn") ->
                "{\"thinking\":{\"type\":\"${if (enabled) "enabled" else "disabled"}\"}}"
            url.contains("siliconflow") -> "{\"enable_thinking\":$enabled}"
            else -> null
        }
    }
}
