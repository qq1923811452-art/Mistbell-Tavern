package com.mistbell.tavern.v3.core.memory

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
        val first = selected.first().id
        val last = selected.last().id
        return MemoryBatchPlan(
            id = "memory:$conversationId:$first:$last",
            conversationId = conversationId,
            turns = selected,
        )
    }

    const val DEFAULT_BATCH_SIZE = 3
}
