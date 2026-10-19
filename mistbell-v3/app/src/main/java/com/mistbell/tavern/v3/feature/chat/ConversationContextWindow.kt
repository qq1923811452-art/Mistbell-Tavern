package com.mistbell.tavern.v3.feature.chat

/**
 * Bounds chat context before prompt assembly.
 *
 * Older messages are represented as short extractive notes rather than copied wholesale.
 * This is deliberately deterministic and offline; it does not claim to be an LLM-generated
 * semantic summary.
 */
data class ConversationContextWindow(
    val earlierSummary: String,
    val recentMessages: List<PromptMessage>,
)

object ConversationContextWindowBuilder {
    const val DEFAULT_RECENT_TURNS = 20
    const val DEFAULT_SUMMARY_MAX_CHARS = 300

    fun build(
        history: List<PromptMessage>,
        recentTurns: Int = DEFAULT_RECENT_TURNS,
        summaryMaxChars: Int = DEFAULT_SUMMARY_MAX_CHARS,
    ): ConversationContextWindow {
        require(recentTurns >= 0) { "recentTurns must be non-negative." }
        require(summaryMaxChars >= 0) { "summaryMaxChars must be non-negative." }
        require(history.all { it.role == PromptRole.USER || it.role == PromptRole.ASSISTANT }) {
            "Conversation history may contain only user and assistant messages."
        }

        val recentMessageCount = recentTurns * 2
        val splitAt = (history.size - recentMessageCount).coerceAtLeast(0)
        val older = history.take(splitAt)
        val recent = history.drop(splitAt)
        return ConversationContextWindow(
            earlierSummary = buildExtractiveSummary(older, summaryMaxChars),
            recentMessages = recent,
        )
    }

    private fun buildExtractiveSummary(history: List<PromptMessage>, maxChars: Int): String {
        if (history.isEmpty() || maxChars == 0) return ""
        val snippets = mutableListOf<String>()
        var remaining = maxChars
        for (message in history.asReversed()) {
            if (remaining <= 0) break
            val normalized = message.content.replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) continue
            val label = if (message.role == PromptRole.USER) "用户：" else "助手："
            val separator = if (snippets.isEmpty()) "" else "；"
            val available = remaining - separator.length - label.length
            if (available <= 0) break
            val snippet = normalized.take(available)
            snippets += "$label$snippet"
            remaining -= separator.length + label.length + snippet.length
            if (snippet.length < normalized.length) break
        }
        return snippets.asReversed().joinToString("；")
    }
}
