package com.mistbell.tavern.v3.feature.sessions

/**
 * Metadata for one conversation. Message bodies stay in the chat database; this record only
 * carries what the session list needs, so listing stays cheap regardless of history size.
 */
data class ChatSession(
    val id: String,
    val roleId: String,
    val title: String,
    val preview: String = "",
    val updatedAt: Long = 0L,
    val messageCount: Int = 0,
    val pinned: Boolean = false,
)
