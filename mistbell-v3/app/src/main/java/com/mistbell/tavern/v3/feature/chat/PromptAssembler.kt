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
    val worldbookEntries: List<WorldBookEntry> = emptyList(),
    val conversationHistory: List<PromptMessage> = emptyList(),
    val latestUserMessage: String,
)

/**
 * Builds a cache-friendly layout: the system message contains only stable content, while every
 * per-turn section (summary, activated worldbook, memory, author notes) travels with the latest
 * user message. Providers cache prompt prefixes, so changing only the tail keeps the earlier
 * turns reusable.
 */
object PromptAssembler {
    fun assemble(input: PromptAssemblyInput): List<PromptMessage> {
        require(input.stableSystemPrefix.isNotBlank()) { "A stable system prefix is required." }
        require(input.latestUserMessage.isNotBlank()) { "The latest user message is required." }
        require(input.conversationHistory.all {
            it.role == PromptRole.USER || it.role == PromptRole.ASSISTANT
        }) { "Conversation history may contain only user and assistant messages." }

        val plan = WorldBookPlacement.plan(input.worldbookEntries)
        val stableSections = buildList {
            add(input.stableSystemPrefix.trimEnd())
            input.stableCharacterDefinition?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            appendEntries("Worldbook constants", plan.beforePrompt.filter(WorldBookEntry::constant))
            appendEntries("Worldbook constants after character", plan.afterPrompt.filter(WorldBookEntry::constant))
        }
        val systemMessage = PromptMessage(PromptRole.SYSTEM, stableSections.joinToString("\n\n"))

        val dynamicSections = buildList {
            input.earlierConversationSummary?.trim()?.takeIf(String::isNotEmpty)?.let {
                add("Earlier conversation notes (extractive, not a full transcript):\n$it")
            }
            appendEntries("Worldbook before character", plan.beforePrompt.filterNot(WorldBookEntry::constant))
            appendEntries("Worldbook after character", plan.afterPrompt.filterNot(WorldBookEntry::constant))
            val memoryFacts = input.activeMemoryFacts.map(String::trim).filter(String::isNotEmpty)
            if (memoryFacts.isNotEmpty()) add("Relevant memory facts:\n" + memoryFacts.joinToString("\n") { "- $it" })
            appendEntries("Author note before", plan.anBefore.filterNot(WorldBookEntry::constant))
            appendEntries("Author note after", plan.anAfter.filterNot(WorldBookEntry::constant))
            appendEntries("Example block before", plan.emBefore.filterNot(WorldBookEntry::constant))
            appendEntries("Example block after", plan.emAfter.filterNot(WorldBookEntry::constant))
            val depthSections = plan.byDepth.entries
                .flatMap { (key, entries) ->
                    entries.filterNot(WorldBookEntry::constant).mapNotNull { entry ->
                        entry.content.trim().takeIf(String::isNotEmpty)?.let {
                            "[depth ${key.first} · ${key.second}] $it"
                        }
                    }
                }
            if (depthSections.isNotEmpty()) add("Worldbook depth entries:\n" + depthSections.joinToString("\n\n"))
        }

        val userContent = if (dynamicSections.isEmpty()) {
            input.latestUserMessage.trim()
        } else {
            dynamicSections.joinToString("\n\n") + "\n\n" + input.latestUserMessage.trim()
        }
        return buildList {
            add(systemMessage)
            addAll(input.conversationHistory)
            add(PromptMessage(PromptRole.USER, userContent))
        }
    }

    private fun MutableList<String>.appendEntries(label: String, entries: List<WorldBookEntry>) {
        val contents = entries.mapNotNull { entry ->
            entry.content.trim().takeIf(String::isNotEmpty)
        }
        if (contents.isNotEmpty()) add("$label:\n" + contents.joinToString("\n\n"))
    }
}
