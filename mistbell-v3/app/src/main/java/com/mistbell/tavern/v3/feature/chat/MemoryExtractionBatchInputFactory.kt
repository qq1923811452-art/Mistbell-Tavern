package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionInput
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionQueueRunner
import com.mistbell.tavern.v3.feature.memory.formatMemoryExtractionTurn
import com.mistbell.tavern.v3.feature.memory.stripThinkingBlocks

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
        val observedAtByTurnId = linkedMapOf<String, Long>()

        job.turnIds.forEachIndexed { index, turnId ->
            val messageIndex =
                requireNotNull(byId[turnId]) {
                    "A queued source turn is missing from persisted chat history."
                }
            val user = history[messageIndex]
            require(user.role == "user") { "A queued source turn is not a user message." }
            val assistant = history.getOrNull(messageIndex + 1)
            require(assistant != null && assistant.role == "assistant") {
                "A queued source turn has no assistant reply."
            }
            if (assistant.deliveryState != PersistedChatMessage.DeliveryState.COMPLETE) {
                // A failed or still-pending assistant response can be completed by a later retry.
                // Keep the durable queue job retryable instead of permanently discarding it.
                throw MemoryExtractionQueueRunner.RetryableInputException()
            }
            require(assistant.content.isNotBlank()) { "A queued assistant reply is empty." }

            turns += EligibleConversationTurn(id = turnId, ordinal = (index + 1).toLong())
            val userText = stripThinkingBlocks(user.content)
            val assistantText = stripThinkingBlocks(assistant.content)
            val labelledAssistant =
                assistant.speakerName
                    ?.takeIf(String::isNotBlank)
                    ?.let { name -> GroupPromptAssembler.labelAssistantContent(name, assistantText) }
                    ?: assistantText
            textByTurnId[turnId] = formatMemoryExtractionTurn(userText, labelledAssistant)
            // A zero timestamp would sort the fact as ancient and lose later updates.
            observedAtByTurnId[turnId] =
                user.createdAtEpochMillis.takeIf { it > 0L } ?: (index + 1).toLong()
        }

        return MemoryExtractionInput(
            conversationId = job.conversationId,
            turns = turns,
            textByTurnId = textByTurnId,
            turnObservedAtEpochMillis = observedAtByTurnId,
        )
    }
}
