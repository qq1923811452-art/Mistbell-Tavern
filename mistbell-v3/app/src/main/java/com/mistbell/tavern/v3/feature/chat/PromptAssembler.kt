package com.mistbell.tavern.v3.feature.chat

/** A deterministic prompt layout with exactly one system message. */
enum class PromptRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class PromptMessage(
    val role: PromptRole,
    val content: String,
) {
    init {
        require(content.isNotBlank()) { "Prompt message content must not be blank." }
    }
}

data class PromptAssemblyInput(
    val stableSystemPrefix: String,
    val stableCharacterDefinition: String? = null,
    val activeMemoryFacts: List<String> = emptyList(),
    val activatedWorldbookEntries: List<String> = emptyList(),
    val conversationHistory: List<PromptMessage> = emptyList(),
    val latestUserMessage: String,
)

/**
 * Assembles role card -> worldbook -> memory into one system message, followed by history and
 * the current user message. Selection and token budgeting happen upstream.
 */
object PromptAssembler {
    fun assemble(input: PromptAssemblyInput): List<PromptMessage> {
        require(input.stableSystemPrefix.isNotBlank()) {
            "A stable system prefix is required."
        }
        require(input.latestUserMessage.isNotBlank()) {
            "The latest user message is required."
        }
        require(input.conversationHistory.all {
            it.role == PromptRole.USER || it.role == PromptRole.ASSISTANT
        }) {
            "Conversation history may contain only user and assistant messages."
        }

        val systemSections = buildList {
            add(input.stableSystemPrefix.trimEnd())
            input.stableCharacterDefinition?.trim()?.takeIf(String::isNotEmpty)?.let(::add)

            val worldbookEntries = input.activatedWorldbookEntries
                .map(String::trim)
                .filter(String::isNotEmpty)
            if (worldbookEntries.isNotEmpty()) {
                add("Activated worldbook entries:\n" + worldbookEntries.joinToString("\n\n"))
            }

            val memoryFacts = input.activeMemoryFacts.map(String::trim).filter(String::isNotEmpty)
            if (memoryFacts.isNotEmpty()) {
                add("Relevant memory facts:\n" + memoryFacts.joinToString("\n") { "- $it" })
            }
        }
        val result = mutableListOf(
            PromptMessage(PromptRole.SYSTEM, systemSections.joinToString("\n\n")),
        )
        result += input.conversationHistory
        result += PromptMessage(PromptRole.USER, input.latestUserMessage.trim())
        return result.toList()
    }
}
