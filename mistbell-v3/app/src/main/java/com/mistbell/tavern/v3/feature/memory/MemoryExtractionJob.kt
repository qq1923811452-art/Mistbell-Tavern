package com.mistbell.tavern.v3.feature.memory

/** Durable state for one memory-extraction batch; no API key is stored in this model. */
data class MemoryExtractionJob(
    val id: String,
    val conversationId: String,
    /** Conversation-only SQLite scope; older jobs fall back to [conversationId]. */
    val historyScopeId: String = conversationId,
    val turnIds: List<String>,
    val providerProfileId: String,
    val credentialHandle: String?,
    val createdAtEpochMillis: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMillis: Long = createdAtEpochMillis,
    val state: State = State.PENDING,
    val lastSafeError: String? = null,
) {
    enum class State { PENDING, RUNNING, RETRY_WAIT, COMPLETED, PERMANENT_FAILURE }

    init {
        require(id.isNotBlank() && id.length <= 256)
        require(conversationId.isNotBlank() && conversationId.length <= 256)
        require(historyScopeId.isNotBlank() && historyScopeId.length <= 256)
        require(turnIds.isNotEmpty() && turnIds.size <= 20)
        require(turnIds.all { it.isNotBlank() && it.length <= 256 })
        require(turnIds.distinct().size == turnIds.size)
        require(providerProfileId.isNotBlank() && providerProfileId.length <= 256)
        require(credentialHandle == null || credentialHandle.isNotBlank() && credentialHandle.length <= 256)
        require(createdAtEpochMillis >= 0L && nextAttemptAtEpochMillis >= 0L)
        require(attemptCount in 0..100)
        require(lastSafeError == null || lastSafeError.length <= 512)
    }
    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_ERROR_CHARS = 512
    }
}
