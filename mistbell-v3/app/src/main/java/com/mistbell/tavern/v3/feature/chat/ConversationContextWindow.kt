package com.mistbell.tavern.v3.feature.chat

/**
 * Bounds chat context before prompt assembly.
 *
 * Older messages are represented as short extractive notes rather than copied wholesale.
 * This is deliberately deterministic and offline; it does not claim to be an LLM-generated
 * semantic summary. The persisted chat transcript is never modified by this windowing step.
 */
data class ConversationContextWindow(
    val earlierSummary: String,
    val recentMessages: List<PromptMessage>,
)

object ConversationContextWindowBuilder {
    // Keep enough complete turns for long-form roleplay while remaining bounded.
    const val DEFAULT_RECENT_TURNS = 40
    const val DEFAULT_SUMMARY_MAX_CHARS = 4_000
    const val DEFAULT_MAX_HISTORY_CHARS = 70_000

    fun build(
        history: List<PromptMessage>,
        recentTurns: Int = DEFAULT_RECENT_TURNS,
        summaryMaxChars: Int = DEFAULT_SUMMARY_MAX_CHARS,
        maxHistoryChars: Int = DEFAULT_MAX_HISTORY_CHARS,
    ): ConversationContextWindow {
        require(recentTurns >= 0) { "recentTurns must be non-negative." }
        require(summaryMaxChars >= 0) { "summaryMaxChars must be non-negative." }
        require(maxHistoryChars >= 0) { "maxHistoryChars must be non-negative." }
        require(history.all { it.role == PromptRole.USER || it.role == PromptRole.ASSISTANT }) {
            "Conversation history may contain only user and assistant messages."
        }

        val splitAt = findRecentTurnBoundary(history, recentTurns)
        val older = history.take(splitAt)
        val recent = history.drop(splitAt)
        return ConversationContextWindow(
            earlierSummary = buildExtractiveSummary(older, summaryMaxChars),
            recentMessages = PromptHistoryBudget.select(
                recent,
                maxCharacters = maxHistoryChars,
                maxMessages = recent.size,
            ),
        )
    }

    /**
     * Treat a turn as a user message and all following messages up to the next user message.
     * This avoids assuming every user turn has exactly one assistant response.
     */
    private fun findRecentTurnBoundary(history: List<PromptMessage>, recentTurns: Int): Int {
        if (recentTurns == 0) return history.size
        if (history.isEmpty()) return 0
        var userTurnsSeen = 0
        var boundary = -1
        for (index in history.indices.reversed()) {
            if (history[index].role == PromptRole.USER) {
                userTurnsSeen += 1
                if (userTurnsSeen == recentTurns) {
                    boundary = index
                    break
                }
            }
        }
        if (boundary < 0) boundary = 0
        // Do not start the recent context with an orphaned assistant response when user turns
        // are available; let it fall into the older extractive notes instead.
        if (history.any { it.role == PromptRole.USER } && history[boundary].role == PromptRole.ASSISTANT) {
            boundary = history.indexOfFirst { it.role == PromptRole.USER }.coerceAtLeast(0)
        }
        return boundary
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
