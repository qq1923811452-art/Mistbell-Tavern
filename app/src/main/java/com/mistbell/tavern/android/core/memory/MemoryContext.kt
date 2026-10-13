package com.mistbell.tavern.android.core.memory

/**
 * Converts stored memories into context that can be injected into prompts.
 */
data class MemoryContext(
    val memories: List<MemoryRecord> = emptyList(),
) {
    fun asPromptText(): String {
        if (memories.isEmpty()) return ""

        return buildString {
            append("Relevant memories:\n")
            memories.forEach { memory ->
                append("- ")
                append(memory.content)
                append('\n')
            }
        }
    }
}
