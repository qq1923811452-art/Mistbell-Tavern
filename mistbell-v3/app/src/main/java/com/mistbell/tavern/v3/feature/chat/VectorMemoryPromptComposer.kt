package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.RetrievedMemory
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextResult

/**
 * Adds retrieved local memories to the current request without changing stored chat history.
 *
 * Call this immediately before OpenAiCompatibleChatClient.stream. The returned prompt is an
 * ephemeral request value; callers must not append the generated memory section to conversation
 * history, otherwise it would be resent and duplicated on subsequent turns.
 */
object VectorMemoryPromptComposer {
    fun compose(
        messages: List<PromptMessage>,
        memoryResult: VectorMemoryContextResult,
    ): List<PromptMessage> {
        if (messages.isEmpty()) return messages
        val userIndex = messages.indexOfLast { it.role == PromptRole.USER }
        if (userIndex < 0) return messages

        val memories = (memoryResult as? VectorMemoryContextResult.Ready)?.memories
            ?.filter(RetrievedMemory::textIsUsable)
            .orEmpty()
        if (memories.isEmpty()) return messages

        val section = buildString {
            append(MEMORY_SECTION_HEADER)
            memories.forEachIndexed { index, memory ->
                append("\n")
                append(index + 1)
                append(". ")
                append(memory.text.trim())
            }
        }
        return buildList(messages.size + 1) {
            addAll(messages.subList(0, userIndex))
            add(PromptMessage(PromptRole.SYSTEM, section))
            addAll(messages.subList(userIndex, messages.size))
        }
    }

    private fun RetrievedMemory.textIsUsable(): Boolean =
        text.isNotBlank() && similarity.isFinite()

    const val MEMORY_SECTION_HEADER =
        "Relevant local memories (use as contextual facts; current user instructions take precedence):"
}
