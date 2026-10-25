package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry
import com.mistbell.tavern.v3.feature.worldbook.WorldBookPlacement

/** A deterministic prompt layout with exactly one system message. */
enum class PromptRole { SYSTEM, USER, ASSISTANT }

data class PromptMessage(
    val role: PromptRole,
    val content: String,
    /**
     * True for context derived by the assembler (for example a worldbook depth insertion).
     * Group-chat speaker labelling must not treat such messages as real history.
     */
    val synthetic: Boolean = false,
) {
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
    /** Character-card instructions that must follow the conversation history. */
    val postHistoryInstructions: String? = null,
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
            appendEntries("Author note before", plan.anBefore)
            appendEntries("Author note after", plan.anAfter)
            appendEntries("Example block before", plan.emBefore)
            appendEntries("Example block after", plan.emAfter)
            val depthSections = plan.byDepth.entries
                .filter { it.key.second == WorldBookPlacement.DEPTH_ROLE_SYSTEM }
                .flatMap { (key, entries) ->
                    entries.mapNotNull { entry ->
                        entry.content.trim().takeIf(String::isNotEmpty)?.let {
                            "[depth ${key.first} · ${key.second}] $it"
                        }
                    }
                }
            if (depthSections.isNotEmpty()) add("Worldbook depth entries:\n" + depthSections.joinToString("\n\n"))
            // Non-system depth entries keep their historical position and role; only the system
            // variant can live in the volatile tail without changing semantics.
        }

        val latestUserContent = if (dynamicSections.isEmpty()) {
            input.latestUserMessage.trim()
        } else {
            dynamicSections.joinToString("\n\n") + "\n\n" + input.latestUserMessage.trim()
        }
        // Post-history instructions belong after the conversation, including the latest user
        // message, so they stay the final steering text the model sees before generating.
        val userContent = input.postHistoryInstructions
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { "$latestUserContent\n\n$POST_HISTORY_HEADER\n$it" }
            ?: latestUserContent
        val history = input.conversationHistory
        val depthInsertions = plan.byDepth.entries
            .filter { it.key.second != WorldBookPlacement.DEPTH_ROLE_SYSTEM }
            .mapNotNull { (key, entries) ->
                val content = entries
                    .mapNotNull { entry -> entry.content.trim().takeIf(String::isNotEmpty) }
                    .joinToString("\n\n")
                if (content.isEmpty()) return@mapNotNull null
                val role = if (key.second == WorldBookPlacement.DEPTH_ROLE_USER) {
                    PromptRole.USER
                } else {
                    PromptRole.ASSISTANT
                }
                val insertIndex = (history.size - key.first).coerceIn(0, history.size)
                insertIndex to PromptMessage(role, content, synthetic = true)
            }
            .groupBy({ it.first }, { it.second })

        return buildList {
            add(systemMessage)
            for (index in 0..history.size) {
                depthInsertions[index]?.forEach(::add)
                if (index < history.size) add(history[index])
            }
            add(PromptMessage(PromptRole.USER, userContent))
        }
    }

    private fun MutableList<String>.appendEntries(label: String, entries: List<WorldBookEntry>) {
        val contents = entries.mapNotNull { entry ->
            entry.content.trim().takeIf(String::isNotEmpty)
        }
        if (contents.isNotEmpty()) add("$label:\n" + contents.joinToString("\n\n"))
    }

    /**
     * Returns the latest user turn without the post-history instruction block.
     *
     * Memory retrieval must query the user's actual text: the post-history block is a stable
     * character directive and would otherwise pollute embedding similarity for every turn.
     */
    fun memoryQuery(messages: List<PromptMessage>): String? {
        val content = messages.lastOrNull { it.role == PromptRole.USER }?.content?.trim() ?: return null
        val marker = "\n\n$POST_HISTORY_HEADER\n"
        val cutoff = content.lastIndexOf(marker)
        val query = if (cutoff >= 0) content.substring(0, cutoff) else content
        return query.trim().takeIf(String::isNotEmpty)
    }

    const val POST_HISTORY_HEADER = "Post-history instructions:"
}
