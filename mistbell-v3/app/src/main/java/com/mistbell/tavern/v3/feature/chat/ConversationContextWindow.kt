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
    val maxHistoryChars: Int = ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS,
    /** Older-prefix size passed to summary extraction, including when extraction is disabled. */
    val archivedMessageCount: Int = 0,
    val pinnedPrefixMessages: List<PromptMessage> = emptyList(),
) {
    /** UTF-16 content characters only, not tokens or the size of the assembled prompt. */
    val recentHistoryChars: Long = recentMessages.sumOf { it.content.length.toLong() }
    val pinnedPrefixChars: Long = pinnedPrefixMessages.sumOf { it.content.length.toLong() }
    val retainedHistoryChars: Long = pinnedPrefixChars + recentHistoryChars

    /** The latest whole turn may exceed this soft budget; callers may still send it. */
    val isOverHistoryBudget: Boolean = retainedHistoryChars > maxHistoryChars.toLong()
}

object ConversationContextWindowBuilder {
    // Soft UTF-16 content-character budget, not a token-safe cap on the assembled prompt.
    const val DEFAULT_RECENT_TURNS = 40
    const val DEFAULT_SUMMARY_MAX_CHARS = 4_000
    const val DEFAULT_MAX_HISTORY_CHARS = 24_000

    @Deprecated("Message-block alignment can split user turns; the builder now uses whole turns.")
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

        // Both outputs use this final boundary. Never trim recent again after summarizing older.
        val splitAt = findRecentBoundary(history, recentTurns, maxHistoryChars)
        return ConversationContextWindow(
            earlierSummary = buildExtractiveSummary(history.take(splitAt), summaryMaxChars),
            recentMessages = history.drop(splitAt),
            maxHistoryChars = maxHistoryChars,
            archivedMessageCount = splitAt,
        )
    }

    /**
     * A turn starts at USER and ends just before the next USER (possibly with no assistant).
     * Opening assistants belong to older when user turns exist. An assistant-only transcript
     * is one indivisible opening segment, retaining the previous no-user fallback.
     *
     * Select a contiguous suffix of whole turns within both limits. For a positive budget the
     * newest turn is always kept whole, even when it alone exceeds the soft character limit.
     * Zero turns or zero characters explicitly selects no recent history.
     * Fixed message-block alignment is intentionally not used: it can split a user turn.
     */
    private fun findRecentBoundary(
        history: List<PromptMessage>,
        recentTurns: Int,
        maxHistoryChars: Int,
    ): Int {
        if (recentTurns == 0 || maxHistoryChars == 0 || history.isEmpty()) return history.size
        val turnStarts =
            history.indices.filter { history[it].role == PromptRole.USER }
                .ifEmpty { listOf(0) }
        var splitAt = history.size
        var turnEnd = history.size
        var selectedTurns = 0
        var usedCharacters = 0L
        var canSelectOlder = true
        val turnLimit = minOf(recentTurns, turnStarts.size)
        while (selectedTurns < turnLimit && canSelectOlder) {
            val turnStart = turnStarts[turnStarts.lastIndex - selectedTurns]
            val turnCharacters = history.subList(turnStart, turnEnd).sumOf { it.content.length.toLong() }
            val nextCharacters = usedCharacters + turnCharacters
            if (selectedTurns == 0 || nextCharacters <= maxHistoryChars.toLong()) {
                splitAt = turnStart
                usedCharacters = nextCharacters
                selectedTurns += 1
                turnEnd = turnStart
            } else {
                canSelectOlder = false
            }
            canSelectOlder = canSelectOlder && usedCharacters <= maxHistoryChars.toLong()
        }
        return splitAt
    }

    /**
     * Reserve about one third of the budget for the oldest messages (initial character/context
     * facts) and the remaining two thirds for the most recent older messages. This is extractive,
     * not semantic, but prevents the opening of a long roleplay from disappearing entirely.
     */
    internal fun summarizeOlder(
        history: List<PromptMessage>,
        maxChars: Int,
    ): String = buildExtractiveSummary(history, maxChars)

    private fun buildExtractiveSummary(
        history: List<PromptMessage>,
        maxChars: Int,
    ): String {
        if (history.isEmpty() || maxChars == 0) return ""
        val normalized =
            history.mapNotNull { message ->
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
        val sections =
            buildList {
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
        val end =
            length.coerceAtMost(maxChars.coerceAtLeast(0)).let { limit ->
                if (limit > 0 && limit < length && this[limit - 1].isHighSurrogate() && this[limit].isLowSurrogate()) {
                    limit - 1
                } else {
                    limit
                }
            }
        return take(end)
    }
}
