package com.mistbell.tavern.v3.core.memory

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A conversation turn is one completed user/assistant exchange, not an individual message.
 * The storage layer must durably record a returned plan as pending before dispatching its API call.
 */
data class EligibleConversationTurn(
    val id: String,
    val ordinal: Long,
) {
    init {
        require(id.isNotBlank()) { "Turn ID must not be blank." }
        require(ordinal >= 0) { "Turn ordinal must not be negative." }
    }
}

data class MemoryBatchPlan(
    val id: String,
    val conversationId: String,
    val turns: List<EligibleConversationTurn>,
) {
    init {
        require(id.isNotBlank())
        require(conversationId.isNotBlank())
        require(turns.isNotEmpty())
    }
}

/**
 * Deterministic batching policy. It selects the oldest unclaimed complete batch and never
 * includes turns already pending, processing, or completed. Persist the plan before API work;
 * retry the same persisted plan after transient errors instead of generating a new batch ID.
 */
object MemoryBatchPolicy {
    fun nextBatch(
        conversationId: String,
        orderedTurns: List<EligibleConversationTurn>,
        claimedTurnIds: Set<String>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        flushPartialBatch: Boolean = false,
    ): MemoryBatchPlan? {
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank." }
        require(batchSize > 0) { "Batch size must be positive." }
        require(orderedTurns.map(EligibleConversationTurn::id).distinct().size == orderedTurns.size) {
            "Turn IDs must be unique."
        }
        require(orderedTurns.map(EligibleConversationTurn::ordinal).distinct().size == orderedTurns.size) {
            "Turn ordinals must be unique."
        }

        val pending = orderedTurns
            .sortedBy(EligibleConversationTurn::ordinal)
            .filterNot { it.id in claimedTurnIds }
        if (pending.size < batchSize && !(flushPartialBatch && pending.isNotEmpty())) {
            return null
        }

        val selected = pending.take(batchSize)
        return MemoryBatchPlan(
            id = stablePlanId(conversationId, selected),
            conversationId = conversationId,
            turns = selected,
        )
    }

    private fun stablePlanId(
        conversationId: String,
        selected: List<EligibleConversationTurn>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        updateString(conversationId)
        selected.forEach { turn ->
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(turn.ordinal).array())
            updateString(turn.id)
        }
        return "memory:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    const val DEFAULT_BATCH_SIZE = 3
}
