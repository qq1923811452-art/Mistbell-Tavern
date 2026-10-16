package com.mistbell.tavern.v3.feature.chat

/**
 * Only the stable system prefix is eligible to remain byte-for-byte identical across turns.
 * Memories and activated worldbook entries are deliberately appended after that prefix so
 * volatile context does not churn the cacheable prefix.
 */
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
 * Deterministic prompt ordering policy. Selection and token budgeting happen upstream; this
 * class never silently truncates content or changes the meaning of selected facts.
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

        val stableParts = listOfNotNull(
            input.stableSystemPrefix.trimEnd(),
            input.stableCharacterDefinition?.trim()?.takeIf(String::isNotEmpty),
        )
        val result = mutableListOf(PromptMessage(PromptRole.SYSTEM, stableParts.joinToString("\n\n")))

        val memoryFacts = input.activeMemoryFacts.map(String::trim).filter(String::isNotEmpty)
        val worldbookEntries = input.activatedWorldbookEntries.map(String::trim).filter(String::isNotEmpty)
        val dynamicSections = buildList {
            if (memoryFacts.isNotEmpty()) {
                add("Relevant memory facts:\n" + memoryFacts.joinToString("\n") { "- $it" })
            }
            if (worldbookEntries.isNotEmpty()) {
                add("Activated worldbook entries:\n" + worldbookEntries.joinToString("\n\n"))
            }
        }
        if (dynamicSections.isNotEmpty()) {
            result += PromptMessage(PromptRole.SYSTEM, dynamicSections.joinToString("\n\n"))
        }

        result += input.conversationHistory
        result += PromptMessage(PromptRole.USER, input.latestUserMessage.trim())
        return result.toList()
    }
}
