package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionInput
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.feature.memory.formatMemoryExtractionTurn

/**
 * Rebuilds a queued extraction batch from durable chat history.
 *
 * It deliberately fails closed when even one requested turn is absent, incomplete, or no longer
 * forms a user/assistant pair. A retry must never silently extract a different or partial batch.
 */
class MemoryExtractionBatchInputFactory {
    fun create(
        job: MemoryExtractionJob,
        history: List<PersistedChatMessage>,
    ): MemoryExtractionInput {
        require(history.map(PersistedChatMessage::id).distinct().size == history.size) {
            "Persisted chat history contains duplicate message IDs."
        }
        val byId = history.withIndex().associate { it.value.id to it.index }
        val turns = mutableListOf<EligibleConversationTurn>()
        val textByTurnId = linkedMapOf<String, String>()

        job.turnIds.forEachIndexed { index, turnId ->
            val messageIndex = requireNotNull(byId[turnId]) {
                "A queued source turn is missing from persisted chat history."
            }
            val user = history[messageIndex]
            require(user.role == "user") { "A queued source turn is not a user message." }
            val assistant = history.getOrNull(messageIndex + 1)
            require(
                assistant != null &&
                    assistant.role == "assistant" &&
                    assistant.deliveryState == PersistedChatMessage.DeliveryState.COMPLETE,
            ) { "A queued source turn has no completed assistant reply." }
            require(assistant.content.isNotBlank()) { "A queued assistant reply is empty." }

            turns += EligibleConversationTurn(id = turnId, ordinal = (index + 1).toLong())
            textByTurnId[turnId] = formatMemoryExtractionTurn(user.content, assistant.content)
        }

        return MemoryExtractionInput(
            conversationId = job.conversationId,
            turns = turns,
            textByTurnId = textByTurnId,
        )
    }
}
