package com.mistbell.tavern.v3.feature.chat

/**
 * Small, UI-facing state owned by [ChatViewModel].
 *
 * Keep large histories, API credentials, and process-level engine objects out of this state.
 */
data class ChatUiState(
    val draft: String = "",
)
