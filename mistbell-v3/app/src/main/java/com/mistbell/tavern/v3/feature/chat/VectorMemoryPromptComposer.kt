package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.memory.RetrievedMemory
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextResult

/**
 * Adds retrieved local memories to the existing first system message, never creating a second
 * system message. The returned prompt is ephemeral and is not appended to chat history.
 */
object VectorMemoryPromptComposer {
    fun compose(
        messages: List<PromptMessage>,
        memoryResult: VectorMemoryContextResult,
    ): List<PromptMessage> {
        val userIndex = messages.indexOfLast { it.role == PromptRole.USER }
        if (userIndex < 0) return messages
        val memories = (memoryResult as? VectorMemoryContextResult.Ready)?.memories
            ?.filter { it.textIsUsable() }
            ?.distinctBy { it.text.trim() }
            .orEmpty()
            .takeWhileWithBudget()
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
        // Memories are volatile: keep them out of the system message so the stable prefix stays
        // byte-identical and provider prefix caches keep hitting.
        return messages.toMutableList().also { result ->
            val target = result[userIndex]
            val existingContent = target.content.trim()
            val combinedContent = if (existingContent.isBlank()) section else "$section\n\n$existingContent"
            result[userIndex] = target.copy(content = combinedContent)
        }
    }

    private fun List<RetrievedMemory>.takeWhileWithBudget(): List<RetrievedMemory> {
        var used = 0
        return takeWhile { memory ->
            used += memory.text.length
            used <= MAX_MEMORY_SECTION_CHARS
        }
    }

    private fun RetrievedMemory.textIsUsable(): Boolean =
        text.isNotBlank() && similarity.isFinite()

    const val MAX_MEMORY_SECTION_CHARS = 1_200

    const val MEMORY_SECTION_HEADER =
        "Relevant local memories (use as contextual facts; current user instructions take precedence):"
}
