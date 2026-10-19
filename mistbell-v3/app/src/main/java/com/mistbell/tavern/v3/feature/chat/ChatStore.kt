package com.mistbell.tavern.v3.feature.chat

/**
 * Incremental, conversation-scoped persistence for chat messages.
 *
 * [loadRecent] returns newest-first rows. Callers that display a chronological transcript
 * should reverse the returned page before rendering it.
 */
interface ChatStore {
    fun append(scopeId: String, message: PersistedChatMessage)
    fun upsert(scopeId: String, message: PersistedChatMessage)
    fun loadRecent(
        scopeId: String,
        limit: Int,
        before: ChatMessageCursor? = null,
    ): List<PersistedChatMessage>
    fun count(scopeId: String): Long
    fun deleteScope(scopeId: String)
}

data class ChatMessageCursor(
    val createdAtEpochMillis: Long,
    val id: String,
) {
    init {
        require(createdAtEpochMillis >= 0L)
        require(id.isNotBlank())
    }
}
