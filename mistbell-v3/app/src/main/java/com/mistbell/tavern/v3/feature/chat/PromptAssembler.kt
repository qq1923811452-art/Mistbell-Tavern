package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry
import com.mistbell.tavern.v3.feature.worldbook.WorldBookPlacement

/** A deterministic prompt layout with exactly one system message. */
enum class PromptRole { SYSTEM, USER, ASSISTANT }

data class PromptMessage(val role: PromptRole, val content: String) {
    init {
        require(role == PromptRole.SYSTEM || content.isNotBlank()) {
            "User and assistant prompt message content must not be blank."
        }
    }
}

data class PromptAssemblyInput(
    val stableSystemPrefix: String,
    val stableCharacterDefinition: String? = null,
    val activeMemoryFacts: List<String> = emptyList(),
    val earlierConversationSummary: String? = null,
    val activatedWorldbookEntries: List<String> = emptyList(),
    val worldbookEntries: List<WorldBookEntry> = emptyList(),
    val conversationHistory: List<PromptMessage> = emptyList(),
    val latestUserMessage: String,
)

/** Stable prefix first; variable worldbook/memory follows it. */
object PromptAssembler {
    fun assemble(input: PromptAssemblyInput): List<PromptMessage> {
        require(input.stableSystemPrefix.isNotBlank()) { "A stable system prefix is required." }
        require(input.latestUserMessage.isNotBlank()) { "The latest user message is required." }
        require(input.conversationHistory.all {
            it.role == PromptRole.USER || it.role == PromptRole.ASSISTANT
        }) { "Conversation history may contain only user and assistant messages." }

        val plan = WorldBookPlacement.plan(input.worldbookEntries)
        val systemSections = buildList {
            add(input.stableSystemPrefix.trimEnd())
            appendEntries("Worldbook before character", plan.beforePrompt)
            input.stableCharacterDefinition?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            input.earlierConversationSummary?.trim()?.takeIf(String::isNotEmpty)?.let {
                add("Earlier conversation notes (extractive, not a full transcript):\n$it")
            }
            val legacyEntries = input.activatedWorldbookEntries.map(String::trim).filter(String::isNotEmpty)
            if (legacyEntries.isNotEmpty()) add("Activated worldbook entries:\n" + legacyEntries.joinToString("\n\n"))
            appendEntries("Worldbook after character", plan.afterPrompt)
            val memoryFacts = input.activeMemoryFacts.map(String::trim).filter(String::isNotEmpty)
            if (memoryFacts.isNotEmpty()) add("Relevant memory facts:\n" + memoryFacts.joinToString("\n") { "- $it" })
            appendEntries("Author note before", plan.anBefore)
            appendEntries("Author note after", plan.anAfter)
            appendEntries("Example block before", plan.emBefore)
            appendEntries("Example block after", plan.emAfter)
        }
        val result = mutableListOf(PromptMessage(PromptRole.SYSTEM, systemSections.joinToString("\n\n")))
        val history = input.conversationHistory
        val insertionsByIndex = plan.byDepth.entries.mapNotNull { (key, entries) ->
            val content = entries.map { it.content.trim() }.filter(String::isNotEmpty).joinToString("\n\n")
            if (content.isEmpty()) return@mapNotNull null
            val role = when (key.second) {
                WorldBookPlacement.DEPTH_ROLE_USER -> PromptRole.USER
                WorldBookPlacement.DEPTH_ROLE_ASSISTANT -> PromptRole.ASSISTANT
                else -> PromptRole.SYSTEM
            }
            (history.size - key.first).coerceIn(0, history.size) to
                (key.first to PromptMessage(role, content))
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, values) ->
                values.sortedWith(compareBy({ it.first }, { it.second.role.name })).map { it.second }
            }
        for (index in 0..history.size) {
            result += insertionsByIndex[index].orEmpty()
            if (index < history.size) result += history[index]
        }
        result += PromptMessage(PromptRole.USER, input.latestUserMessage.trim())
        return result.toList()
    }

    private fun MutableList<String>.appendEntries(title: String, entries: List<WorldBookEntry>) {
        val content = entries.map { it.content.trim() }.filter(String::isNotEmpty)
        if (content.isNotEmpty()) add("$title:\n" + content.joinToString("\n\n"))
    }
}
