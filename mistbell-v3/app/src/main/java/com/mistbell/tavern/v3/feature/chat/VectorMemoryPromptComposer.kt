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
        val memories =
            (memoryResult as? VectorMemoryContextResult.Ready)?.memories
                ?.filter { it.textIsUsable() }
                ?.distinctBy { it.text.trim() }
                .orEmpty()
                .takeWhileWithBudget()
        if (memories.isEmpty()) return messages

        val section =
            buildString {
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
        val usable =
            mapNotNull { memory ->
                val text = memory.text.trim()
                if (text.isEmpty()) null else memory.copy(text = text)
            }
        val first = usable.firstOrNull()
        return if (first != null && first.text.length > MAX_MEMORY_SECTION_CHARS) {
            // A single oversized memory is more useful truncated than dropped entirely.
            val end = truncationEnd(first.text, MAX_MEMORY_SECTION_CHARS)
            if (end > 0) listOf(first.copy(text = first.text.substring(0, end))) else emptyList()
        } else {
            var used = 0
            usable.takeWhile { memory ->
                used += memory.text.length
                used <= MAX_MEMORY_SECTION_CHARS
            }
        }
    }

    /** Never leave half of a UTF-16 surrogate pair at the truncation boundary. */
    private fun truncationEnd(
        text: String,
        budget: Int,
    ): Int {
        val proposedEnd = minOf(text.length, budget)
        return if (proposedEnd in 1 until text.length && Character.isHighSurrogate(text[proposedEnd - 1])) {
            proposedEnd - 1
        } else {
            proposedEnd
        }
    }

    private fun RetrievedMemory.textIsUsable(): Boolean = text.isNotBlank() && similarity.isFinite()

    /** Upper bound for memory text; numbering and the section header are not counted. */
    const val MAX_MEMORY_SECTION_CHARS = 1_200

    const val MEMORY_SECTION_HEADER =
        "Relevant local memories (use as contextual facts; current user instructions take precedence):"
}
