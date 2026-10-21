package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences

/**
 * Owns the existing preference-backed conversation history window construction.
 */
internal class ChatPromptHistoryCoordinator(
    private val settings: SharedPreferences,
) {
    fun build(history: List<PromptMessage>) = ConversationContextWindowBuilder.build(
        history = history,
        recentTurns = settings.getInt(
            "chat_history_recent_turns",
            ConversationContextWindowBuilder.DEFAULT_RECENT_TURNS,
        ).coerceIn(1, 100),
        summaryMaxChars = settings.getInt(
            "chat_history_summary_max_chars",
            ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS,
        ).coerceIn(0, 10_000),
        maxHistoryChars = settings.getInt(
            "chat_history_max_chars",
            ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS,
        ).coerceIn(4_000, 100_000),
    )
}
