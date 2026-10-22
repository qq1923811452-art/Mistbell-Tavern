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
        val systemIndex = messages.indexOfFirst { it.role == PromptRole.SYSTEM }
        if (systemIndex < 0) return messages
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
        return messages.toMutableList().also { result ->
            val system = result[systemIndex]
            val existingContent = system.content.trimEnd()
            val combinedContent = if (existingContent.isBlank()) section else "$existingContent\n\n$section"
            result[systemIndex] = system.copy(content = combinedContent)
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

    /** Upper bound for memory text; numbering and the section header are not counted. */
    const val MAX_MEMORY_SECTION_CHARS = 1_200

    const val MEMORY_SECTION_HEADER =
        "Relevant local memories (use as contextual facts; current user instructions take precedence):"
}
