package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.settings.getIntCompat

/** Reads history preferences while the screen-owned planner retains session-memory anchors. */
internal class ChatPromptHistoryCoordinator(
    private val settings: SharedPreferences,
    private val planner: StableConversationWindowPlanner,
    private val cache: WindowStateCache? = null,
) {
    fun build(scopeId: String, history: List<ChatLine>, invalidate: Boolean): HistoryWindowDecision {
        val source = history.map { line ->
            WindowHistoryMessage(
                id = line.id,
                role = if (line.role == "user") PromptRole.USER else PromptRole.ASSISTANT,
                content = line.content,
                variants = line.variants,
                activeVariant = line.activeVariant,
                sourceRole = line.role,
            )
        }
        val config = HistoryWindowConfig(
            recentTurns = settings.getIntCompat(
                "chat_history_recent_turns",
                ConversationContextWindowBuilder.DEFAULT_RECENT_TURNS,
            ).coerceIn(1, MAX_RECENT_TURNS),
            summaryMaxChars = settings.getIntCompat(
                "chat_history_summary_max_chars",
                ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS,
            ).coerceIn(0, MAX_SUMMARY_CHARS),
            maxHistoryChars = settings.getIntCompat(
                "chat_history_max_chars",
                ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS,
            ).coerceIn(MIN_HISTORY_CHARS, MAX_HISTORY_CHARS),
        )
        if (!invalidate && !planner.hasState()) {
            runCatching { cache?.load(scopeId) }.getOrNull()?.let { state ->
                planner.restore(state, scopeId, source, config)
            }
        }
        val decision = planner.plan(scopeId, source, config, invalidate)
        planner.exportState()?.let { state -> runCatching { cache?.save(state) } }
        return decision
    }
}

private const val MAX_RECENT_TURNS = 100
private const val MAX_SUMMARY_CHARS = 10_000
private const val MIN_HISTORY_CHARS = 4_000
private const val MAX_HISTORY_CHARS = 2_000_000
