package com.mistbell.tavern.v3.feature.chat

/** Formats server-reported counters only; reasoning text is never part of the summary. */
internal fun formatUsageSummary(usage: ChatTokenUsage): String {
    val cached = usage.cachedPromptTokens
    val cacheRatio =
        if (cached != null && usage.promptTokens > 0) {
            "（${cached * PERCENT / usage.promptTokens}%）"
        } else {
            ""
        }
    val reasoning = usage.reasoningTokens?.let { " · 推理 $it token" }.orEmpty()
    return "Token 用量：输入 ${usage.promptTokens} · 缓存 ${cached?.toString() ?: "未知"}$cacheRatio" +
        " · 输出 ${usage.completionTokens} · 合计 ${usage.totalTokens}$reasoning"
}

private const val PERCENT = 100
