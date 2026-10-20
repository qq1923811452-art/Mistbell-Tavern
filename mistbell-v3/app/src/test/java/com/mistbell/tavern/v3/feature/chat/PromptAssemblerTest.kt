package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry
import com.mistbell.tavern.v3.feature.worldbook.WorldBookPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {
    @Test
    fun stablePrefixDoesNotChangeWhenVolatileContextChanges() {
        val first = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Follow the roleplay rules.",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Mira likes tea."),
                worldbookEntries = listOf(WorldBookEntry(
                    id = "city", bookId = "book", content = "The city has three moons.",
                    insertPosition = WorldBookPlacement.POSITION_AFTER_PROMPT, constant = true,
                )),
                latestUserMessage = "Hello",
            ),
        )
        val second = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Follow the roleplay rules.",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Mira now prefers coffee."),
                worldbookEntries = listOf(WorldBookEntry(
                    id = "city", bookId = "book", content = "The city has four moons.",
                    insertPosition = WorldBookPlacement.POSITION_AFTER_PROMPT, constant = true,
                )),
                latestUserMessage = "Good morning",
            ),
        )
        assertEquals(PromptRole.SYSTEM, first.first().role)
        assertTrue(first.first().content.startsWith("Follow the roleplay rules."))
        assertEquals(1, first.count { it.role == PromptRole.SYSTEM })
        assertEquals(1, second.count { it.role == PromptRole.SYSTEM })
    }

    @Test
    fun assemblesCharacterWorldbookMemoryHistoryAndCurrentUser() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Role rules",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Fact A"),
                worldbookEntries = listOf(WorldBookEntry(
                    id = "world", bookId = "book", content = "World B",
                    insertPosition = WorldBookPlacement.POSITION_AFTER_PROMPT, constant = true,
                )),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "Earlier"),
                    PromptMessage(PromptRole.ASSISTANT, "Earlier reply"),
                ),
                latestUserMessage = "Latest",
            ),
        )
        assertEquals(1, messages.count { it.role == PromptRole.SYSTEM })
        val system = messages[0].content
        assertTrue(system.indexOf("Character: Mira") < system.indexOf("Worldbook after character"))
        assertTrue(system.indexOf("Worldbook after character") < system.indexOf("Relevant memory facts"))
        assertEquals(PromptRole.USER, messages[1].role)
        assertEquals("Earlier", messages[1].content)
        assertEquals(PromptRole.ASSISTANT, messages[2].role)
        assertEquals("Earlier reply", messages[2].content)
        assertEquals(PromptRole.USER, messages.last().role)
        assertEquals("Latest", messages.last().content)
    }

    @Test
    fun earlierConversationSummaryUsesAnActualLineBreak() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                earlierConversationSummary = "Mira is waiting at the station.",
                latestUserMessage = "Hi",
            ),
        )
        assertTrue(messages.first().content.contains(
            "Earlier conversation notes (extractive, not a full transcript):\nMira is waiting at the station.",
        ))
    }

    @Test
    fun blankDynamicEntriesAreFilteredWithoutCreatingEmptyMessages() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                activeMemoryFacts = listOf("", "  "),
                worldbookEntries = listOf(WorldBookEntry(id = "blank", bookId = "book", content = "")),
                latestUserMessage = "Hi",
            ),
        )
        assertEquals(2, messages.size)
        assertEquals(1, messages.count { it.role == PromptRole.SYSTEM })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSystemMessagesInsideConversationHistory() {
        PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                conversationHistory = listOf(PromptMessage(PromptRole.SYSTEM, "Unexpected")),
                latestUserMessage = "Hi",
            ),
        )
    }
}
