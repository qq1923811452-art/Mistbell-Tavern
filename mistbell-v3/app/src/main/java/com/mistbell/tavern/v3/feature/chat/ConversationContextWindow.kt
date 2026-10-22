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
    // Character counts are not token counts; keep the default conservative for smaller contexts.
    const val DEFAULT_RECENT_TURNS = 40
    const val DEFAULT_SUMMARY_MAX_CHARS = 4_000
    const val DEFAULT_MAX_HISTORY_CHARS = 24_000
    // Freeze the summary over fixed blocks so the prompt prefix stops changing every turn.
    const val SUMMARY_BLOCK_MESSAGES = 10

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

        val boundary = findRecentTurnBoundary(history, recentTurns)
        val splitAt = if (recentTurns > 0 && boundary >= SUMMARY_BLOCK_MESSAGES) {
            boundary / SUMMARY_BLOCK_MESSAGES * SUMMARY_BLOCK_MESSAGES
        } else {
            boundary
        }
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

    /**
     * Reserve about one third of the budget for the oldest messages (initial character/context
     * facts) and the remaining two thirds for the most recent older messages. This is extractive,
     * not semantic, but prevents the opening of a long roleplay from disappearing entirely.
     */
    private fun buildExtractiveSummary(history: List<PromptMessage>, maxChars: Int): String {
        if (history.isEmpty() || maxChars == 0) return ""
        val normalized = history.mapNotNull { message ->
            val content = message.content.replace(Regex("\\s+"), " ").trim()
            if (content.isEmpty()) null else message to content
        }
        if (normalized.isEmpty()) return ""

        val oldestCount = (normalized.size + 1) / 2
        val oldest = normalized.take(oldestCount)
        val newest = normalized.drop(oldestCount)
        val oldestBudget = if (newest.isEmpty()) maxChars else maxChars / 3
        val newestBudget = if (oldest.isEmpty()) maxChars else (maxChars - oldestBudget - 1).coerceAtLeast(0)
        val oldestSnippets = collectSnippets(oldest, oldestBudget, newestFirst = false)
        val newestSnippets = collectSnippets(newest, newestBudget, newestFirst = true)
        val sections = buildList {
            addAll(oldestSnippets)
            addAll(newestSnippets)
        }
        return sections.joinToString("；").takeWithoutSplittingSurrogatePair(maxChars)
    }

    private fun collectSnippets(
        messages: List<Pair<PromptMessage, String>>,
        maxChars: Int,
        newestFirst: Boolean,
    ): List<String> {
        if (messages.isEmpty() || maxChars <= 0) return emptyList()
        val snippets = mutableListOf<String>()
        var remaining = maxChars
        val ordered = if (newestFirst) messages.asReversed() else messages
        for ((message, normalized) in ordered) {
            if (remaining <= 0) break
            val label = if (message.role == PromptRole.USER) "用户：" else "助手："
            val separatorCost = if (snippets.isEmpty()) 0 else 1
            val available = remaining - separatorCost - label.length
            if (available <= 0) break
            val snippet = normalized.takeWithoutSplittingSurrogatePair(available)
            if (snippet.isEmpty()) continue
            snippets += "$label$snippet"
            remaining -= separatorCost + label.length + snippet.length
            if (snippet.length < normalized.length) break
        }
        return if (newestFirst) snippets.asReversed() else snippets
    }

    private fun String.takeWithoutSplittingSurrogatePair(maxChars: Int): String {
        val end = length.coerceAtMost(maxChars.coerceAtLeast(0)).let { limit ->
            if (limit > 0 && limit < length && this[limit - 1].isHighSurrogate() && this[limit].isLowSurrogate()) {
                limit - 1
            } else {
                limit
            }
        }
        return take(end)
    }
}
