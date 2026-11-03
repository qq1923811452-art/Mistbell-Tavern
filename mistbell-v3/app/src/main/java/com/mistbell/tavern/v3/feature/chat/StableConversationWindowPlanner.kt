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
            "pinnedMessages=${window.pinnedPrefixMessages.size}; pinnedUtf16Chars=${window.pinnedPrefixChars}; " +
            "archivedMessages=${window.archivedMessageCount}; archivedTurns=$archivedTurnCount; " +
            "recentMessages=${window.recentMessages.size}; recentUtf16Chars=${window.recentHistoryChars}; " +
            "maxHistoryChars=${window.maxHistoryChars}; oversize=${window.isOverHistoryBudget}; " +
            "summaryUtf16Chars=${window.earlierSummary.length}; summaryRebuilt=$summaryRebuilt; " +
            "budget=soft-UTF16-not-token-hard-limit"
}

/** Keeps an opening prefix fixed while rolling a bounded, whole-turn recent tail. */
// Keep validation, persistence and boundary transitions together in this small state machine.
@Suppress("TooManyFunctions")
internal class StableConversationWindowPlanner {
    private var previous: WindowSnapshot? = null

    @Synchronized
    fun hasState(): Boolean = previous != null

    @Synchronized
    fun restore(
        state: ConversationWindowState,
        scopeId: String,
        history: List<WindowHistoryMessage>,
        config: HistoryWindowConfig,
    ): Boolean {
        if (previous != null || !state.matches(scopeId, history, config)) return false
        val source = history.take(state.sourceCount)
        val messages = source.filter { it.content.isNotBlank() }
        val pinned = messages.take(state.pinnedMessageCount)
        val window = ConversationContextWindow(
            state.summary,
            messages.drop(state.archivedMessageCount).map(WindowHistoryMessage::toPromptMessage),
            config.maxHistoryChars,
            state.archivedMessageCount,
            pinned.map(WindowHistoryMessage::toPromptMessage),
        )
        val pinLimit = minOf(config.recentTurns / PIN_BUDGET_DIVISOR, MAX_PIN_TURNS)
        val pinFrozen = state.archivedMessageCount > 0 ||
            messages.count { it.role == PromptRole.USER } > pinLimit ||
            messages.sumOf { it.content.length.toLong() } > config.maxHistoryChars.toLong() / PIN_BUDGET_DIVISOR
        previous = WindowSnapshot(
            scopeId, config, source, decision(messages, window, "restored", false),
            state.pinnedMessageCount, pinFrozen,
        )
        return true
    }

    @Synchronized
    fun exportState(): ConversationWindowState? = previous?.let { snapshot ->
        ConversationWindowState(
            version = ConversationWindowState.VERSION,
            scopeId = snapshot.scopeId,
            config = snapshot.config,
            sourceCount = snapshot.history.size,
            sourceFingerprint = WindowSourceFingerprint.hash(snapshot.history),
            startId = snapshot.decision.startId,
            summaryThroughId = snapshot.decision.summaryThroughId,
            archivedMessageCount = snapshot.decision.window.archivedMessageCount,
            pinnedMessageCount = snapshot.pinnedMessageCount,
            summary = snapshot.decision.window.earlierSummary,
        )
    }

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
        val next = if (resetReason != null) {
            initialize(messages, promptHistory, config, resetReason)
        } else {
            advance(messages, promptHistory, config, checkNotNull(old))
        }
        previous = WindowSnapshot(
            scopeId, config, history.map { it.copy(variants = it.variants.toList()) }, next,
            next.window.pinnedPrefixMessages.size,
            (resetReason == null && old?.pinFrozen == true) || next.window.archivedMessageCount > 0 ||
                messages.count { it.role == PromptRole.USER } > minOf(config.recentTurns / PIN_BUDGET_DIVISOR, MAX_PIN_TURNS) ||
                messages.sumOf { it.content.length.toLong() } > config.maxHistoryChars.toLong() / PIN_BUDGET_DIVISOR,
        )
        return next
    }

    private fun initialize(
        messages: List<WindowHistoryMessage>,
        promptHistory: List<PromptMessage>,
        config: HistoryWindowConfig,
        reason: String,
    ): HistoryWindowDecision {
        val disabled = config.recentTurns == 0 || config.maxHistoryChars == 0
        if (disabled) {
            val summary = ConversationContextWindowBuilder.summarizeOlder(
                promptHistory, config.summaryMaxChars,
            )
            val empty = ConversationContextWindow(summary, emptyList(), config.maxHistoryChars, messages.size)
            return decision(messages, empty, reason, true)
        }
        val starts = turnStarts(messages)
        val pinBudget = config.maxHistoryChars.toLong() / PIN_BUDGET_DIVISOR
        val pinTurnLimit = minOf(config.recentTurns / PIN_BUDGET_DIVISOR, MAX_PIN_TURNS)
        val totalChars = messages.sumOf { it.content.length.toLong() }
        val growable = starts.size <= pinTurnLimit && totalChars <= pinBudget
        val pinEnd = if (growable) 0 else selectPrefixBoundary(
            messages, starts, pinBudget, pinTurnLimit,
        )
        val pinChars = messages.take(pinEnd).sumOf { it.content.length.toLong() }
        val pinTurns = messages.take(pinEnd).count { it.role == PromptRole.USER }
        val tailBudget = (config.maxHistoryChars.toLong() - pinChars).coerceAtLeast(0)
        val tailStart = selectTailBoundary(messages, starts, config.recentTurns - pinTurns, tailBudget)
        val split = maxOf(pinEnd, tailStart)
        val summary = ConversationContextWindowBuilder.summarizeOlder(
            promptHistory.subList(pinEnd, split), config.summaryMaxChars,
        )
        val window = ConversationContextWindow(
            summary,
            promptHistory.drop(split),
            config.maxHistoryChars,
            split,
            promptHistory.take(pinEnd),
        )
        return decision(messages, window, reason, true)
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
        var pinCount = old.pinnedMessageCount
        val suffix = messages.drop(oldSplit)
        val starts = turnStarts(suffix)
        val disabled = config.recentTurns == 0 || config.maxHistoryChars == 0
        val pinMessages = messages.take(pinCount)
        val pinTurnCount = pinMessages.count { it.role == PromptRole.USER }
        val pinChars = pinMessages.sumOf { it.content.length.toLong() }
        val tailConfig = config.copy(
            recentTurns = (config.recentTurns - pinTurnCount).coerceAtLeast(0),
            maxHistoryChars = (config.maxHistoryChars -
                pinChars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).coerceAtLeast(0),
        )
        var tail = if (disabled) {
            messages.size
        } else {
            oldSplit + evictionBoundary(suffix, starts, tailConfig)
        }
        val sourceChars = messages.sumOf { it.content.length.toLong() }
        val canGrowPin = !old.pinFrozen && oldSplit == 0 && (
            messages.count { it.role == PromptRole.USER } >
                minOf(config.recentTurns / PIN_BUDGET_DIVISOR, MAX_PIN_TURNS) ||
                sourceChars > config.maxHistoryChars.toLong() / PIN_BUDGET_DIVISOR
            )
        if (!disabled && canGrowPin) {
            pinCount = selectPrefixBoundary(
                messages, turnStarts(messages), config.maxHistoryChars.toLong() / PIN_BUDGET_DIVISOR,
                minOf(config.recentTurns / PIN_BUDGET_DIVISOR, MAX_PIN_TURNS),
            )
            tail = maxOf(tail, pinCount)
        }
        val effectiveSplit = maxOf(oldSplit, tail, pinCount)
        val newPinCount = pinCount
        val rebuilt = effectiveSplit != oldSplit
        val summary = if (rebuilt) {
            ConversationContextWindowBuilder.summarizeOlder(
                promptHistory.subList(newPinCount, effectiveSplit), config.summaryMaxChars,
            )
        } else {
            old.decision.window.earlierSummary
        }
        val reason = when {
            disabled -> "history-disabled"
            effectiveSplit > oldSplit -> "rolling-tail"
            newPinCount > old.pinnedMessageCount -> "pin-grown"
            else -> "anchor-retained"
        }
        val window = ConversationContextWindow(
            summary,
            promptHistory.drop(effectiveSplit),
            config.maxHistoryChars,
            effectiveSplit,
            promptHistory.take(newPinCount),
        )
        return decision(messages, window, reason, rebuilt)
    }

    private fun evictionBoundary(
        suffix: List<WindowHistoryMessage>,
        starts: List<Int>,
        config: HistoryWindowConfig,
    ): Int {
        var removedTurns = 0
        var split = 0
        val tailBudget = config.maxHistoryChars.toLong()
        var chars = suffix.sumOf { it.content.length.toLong() }
        val turnPressure = starts.size >= config.recentTurns + config.blockTurns
        val characterPressure = chars > tailBudget + HIGH_WATER_HEADROOM
        var needsEviction = turnPressure || characterPressure
        while (needsEviction && starts.size - removedTurns > config.blockTurns) {
            removedTurns += config.blockTurns
            val next = starts[removedTurns]
            chars -= suffix.subList(split, next).sumOf { it.content.length.toLong() }
            split = next
            needsEviction = starts.size - removedTurns > config.recentTurns ||
                (characterPressure && chars > tailBudget)
        }
        return split
    }

    private fun selectPrefixBoundary(
        messages: List<WindowHistoryMessage>,
        starts: List<Int>,
        budget: Long,
        turnLimit: Int,
    ): Int {
        if (starts.isEmpty() || budget <= 0 || turnLimit <= 0) return 0
        var end = 0
        var used = 0L
        for (index in starts.indices.take(turnLimit)) {
            val turnEnd = starts.getOrNull(index + 1) ?: messages.size
            val turnStart = if (index == 0) 0 else starts[index]
            val chars = messages.subList(turnStart, turnEnd)
                .sumOf { it.content.length.toLong() }
            if (used + chars > budget) break
            used += chars
            end = turnEnd
        }
        return end
    }

    // Separate exits preserve newest-turn retention and stop before exceeding either soft limit.
    @Suppress("LoopWithTooManyJumpStatements")
    private fun selectTailBoundary(
        messages: List<WindowHistoryMessage>,
        starts: List<Int>,
        turnLimit: Int,
        budget: Long,
    ): Int {
        if (starts.isEmpty()) return 0
        val limit = turnLimit.coerceAtLeast(1)
        var selected = 0
        var chars = 0L
        for (index in starts.lastIndex downTo 0) {
            if (selected >= limit) break
            val end = starts.getOrNull(index + 1) ?: messages.size
            val size = messages.subList(starts[index], end).sumOf { it.content.length.toLong() }
            if (selected > 0 && chars + size > budget) break
            chars += size
            selected++
            if (chars > budget) break
        }
        return starts.getOrNull(starts.size - selected) ?: 0
    }

    private fun turnStarts(messages: List<WindowHistoryMessage>): List<Int> =
        messages.indices.filter { messages[it].role == PromptRole.USER }

    private fun decision(
        history: List<WindowHistoryMessage>,
        window: ConversationContextWindow,
        reason: String,
        summaryRebuilt: Boolean,
    ): HistoryWindowDecision {
        val split = window.archivedMessageCount
        val pinCount = window.pinnedPrefixMessages.size
        return HistoryWindowDecision(
            window = window,
            reason = reason,
            startId = history.getOrNull(split)?.id,
            summaryThroughId = history.getOrNull(split - 1)?.id,
            archivedTurnCount = history.subList(pinCount.coerceAtMost(split), split)
                .count { it.role == PromptRole.USER },
            summaryRebuilt = summaryRebuilt,
        )
    }
}

private data class WindowSnapshot(
    val scopeId: String,
    val config: HistoryWindowConfig,
    val history: List<WindowHistoryMessage>,
    val decision: HistoryWindowDecision,
    val pinnedMessageCount: Int = decision.window.pinnedPrefixMessages.size,
    val pinFrozen: Boolean = false,
)

private const val HIGH_WATER_HEADROOM = 8_000L
private const val MAX_PIN_TURNS = 10
private const val PIN_BUDGET_DIVISOR = 4
