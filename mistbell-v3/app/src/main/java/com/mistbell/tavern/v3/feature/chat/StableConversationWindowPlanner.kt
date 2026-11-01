package com.mistbell.tavern.v3.feature.chat

/** Identity is retained through planning; repeated text is never used to recover message IDs. */
internal data class WindowHistoryMessage(
    val id: String,
    val role: PromptRole,
    val content: String,
    val variants: List<String> = emptyList(),
    val activeVariant: Int = 0,
    val sourceRole: String = role.name,
) {
    fun toPromptMessage() = PromptMessage(role, content)
}

internal data class HistoryWindowConfig(
    val recentTurns: Int = ConversationContextWindowBuilder.DEFAULT_RECENT_TURNS,
    val summaryMaxChars: Int = ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS,
    val maxHistoryChars: Int = ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS,
    val blockTurns: Int = 5,
) {
    init {
        require(recentTurns >= 0 && summaryMaxChars >= 0 && maxHistoryChars >= 0)
        require(blockTurns > 0)
    }
}

internal data class HistoryWindowDecision(
    val window: ConversationContextWindow,
    val reason: String,
    val startId: String?,
    val summaryThroughId: String?,
    val archivedTurnCount: Int,
    val summaryRebuilt: Boolean,
) {
    fun diagnosticText(): String =
        "reason=$reason; startId=$startId; summaryThroughId=$summaryThroughId; " +
            "archivedMessages=${window.archivedMessageCount}; archivedTurns=$archivedTurnCount; " +
            "recentMessages=${window.recentMessages.size}; recentUtf16Chars=${window.recentHistoryChars}; " +
            "maxHistoryChars=${window.maxHistoryChars}; oversize=${window.isOverHistoryBudget}; " +
            "oversizeUtf16Chars=${(window.recentHistoryChars - window.maxHistoryChars).coerceAtLeast(0)}; " +
            "highWaterUtf16Chars=${window.maxHistoryChars.toLong() + HIGH_WATER_HEADROOM}; " +
            "summaryUtf16Chars=${window.earlierSummary.length}; summaryRebuilt=$summaryRebuilt; " +
            "budget=soft-UTF16-not-token-hard-limit"
}

/**
 * Session-memory only: neither a restart-stable cache nor a token hard limit.
 * B1 still owns one contiguous whole-turn boundary. On append, that boundary moves only in
 * complete USER-turn blocks, and summary extraction runs only when its exact older prefix changes.
 * Source validation deliberately compares the entire immutable identity/role/content snapshot.
 */
internal class StableConversationWindowPlanner {
    private var previous: WindowSnapshot? = null

    @Synchronized
    fun plan(
        scopeId: String,
        history: List<WindowHistoryMessage>,
        config: HistoryWindowConfig,
        invalidate: Boolean = false,
    ): HistoryWindowDecision {
        val old = previous
        val resetReason = resetReason(old, scopeId, history, config, invalidate)
        val messages = history.filter { it.content.isNotBlank() }
        val promptHistory = messages.map(WindowHistoryMessage::toPromptMessage)
        val result = if (resetReason != null) {
            val initial = ConversationContextWindowBuilder.build(
                promptHistory, config.recentTurns, config.summaryMaxChars, config.maxHistoryChars,
            )
            decision(messages, initial, resetReason, summaryRebuilt = true)
        } else {
            advance(messages, promptHistory, config, checkNotNull(old))
        }
        previous = WindowSnapshot(
            scopeId, config, history.map { it.copy(variants = it.variants.toList()) }, result,
        )
        return result
    }

    private fun resetReason(
        old: WindowSnapshot?,
        scopeId: String,
        history: List<WindowHistoryMessage>,
        config: HistoryWindowConfig,
        invalidate: Boolean,
    ): String? = when {
        invalidate -> "explicit-invalidation"
        old == null -> "initial"
        old.scopeId != scopeId -> "scope-changed"
        old.config != config -> "config-changed"
        history.size < old.history.size -> "source-changed"
        old.history.indices.any { old.history[it] != history[it] } -> "source-changed"
        else -> null
    }

    private fun advance(
        messages: List<WindowHistoryMessage>,
        promptHistory: List<PromptMessage>,
        config: HistoryWindowConfig,
        old: WindowSnapshot,
    ): HistoryWindowDecision {
        val oldSplit = old.decision.window.archivedMessageCount
        val suffix = messages.drop(oldSplit)
        val turnStarts = suffix.indices.filter { suffix[it].role == PromptRole.USER }
        val chars = suffix.sumOf { it.content.length.toLong() }
        val characterPressure = chars > config.maxHistoryChars.toLong() + HIGH_WATER_HEADROOM
        val turnPressure = turnStarts.size.toLong() >= config.recentTurns.toLong() + config.blockTurns
        val disabled = config.recentTurns == 0 || config.maxHistoryChars == 0
        val removed = if (disabled) {
            suffix.size
        } else if (characterPressure || turnPressure) {
            blockBoundary(suffix, turnStarts, config, characterPressure)
        } else {
            0
        }
        val split = oldSplit + removed
        val rebuilt = split != oldSplit
        val reason = evictionReason(disabled, removed, characterPressure, turnPressure)
        val summary = if (rebuilt) {
            ConversationContextWindowBuilder.summarizeOlder(promptHistory.take(split), config.summaryMaxChars)
        } else {
            old.decision.window.earlierSummary
        }
        val window = ConversationContextWindow(summary, promptHistory.drop(split), config.maxHistoryChars, split)
        return decision(messages, window, reason, rebuilt)
    }

    private fun evictionReason(
        disabled: Boolean,
        removed: Int,
        characterPressure: Boolean,
        turnPressure: Boolean,
    ): String = when {
        disabled -> "history-disabled"
        removed > 0 && characterPressure && turnPressure -> "character-and-turn-high-water"
        removed > 0 && characterPressure -> "character-high-water"
        removed > 0 -> "turn-high-water"
        characterPressure -> "oversize-whole-block-retained"
        else -> "anchor-retained"
    }

    /** Never cut the newest turn, nor remove a partial block merely to satisfy a soft budget. */
    private fun blockBoundary(
        suffix: List<WindowHistoryMessage>,
        turnStarts: List<Int>,
        config: HistoryWindowConfig,
        characterPressure: Boolean,
    ): Int {
        var removedTurns = 0
        var split = 0
        var remainingChars = suffix.sumOf { it.content.length.toLong() }
        var needsEviction = true
        while (needsEviction && turnStarts.size - removedTurns > config.blockTurns) {
            removedTurns += config.blockTurns
            val nextSplit = turnStarts[removedTurns]
            remainingChars -= suffix.subList(split, nextSplit).sumOf { it.content.length.toLong() }
            split = nextSplit
            needsEviction = turnStarts.size - removedTurns > config.recentTurns ||
                (characterPressure && remainingChars >= config.maxHistoryChars.toLong())
        }
        return split
    }

    private fun decision(
        history: List<WindowHistoryMessage>,
        window: ConversationContextWindow,
        reason: String,
        summaryRebuilt: Boolean,
    ): HistoryWindowDecision {
        val split = window.archivedMessageCount
        return HistoryWindowDecision(
            window = window,
            reason = reason,
            startId = history.getOrNull(split)?.id,
            summaryThroughId = history.getOrNull(split - 1)?.id,
            archivedTurnCount = history.take(split).count { it.role == PromptRole.USER },
            summaryRebuilt = summaryRebuilt,
        )
    }
}

private data class WindowSnapshot(
    val scopeId: String,
    val config: HistoryWindowConfig,
    val history: List<WindowHistoryMessage>,
    val decision: HistoryWindowDecision,
)

private const val HIGH_WATER_HEADROOM = 8_000L
