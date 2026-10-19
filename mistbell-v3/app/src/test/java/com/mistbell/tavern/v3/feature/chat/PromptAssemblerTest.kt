package com.mistbell.tavern.v3.feature.chat

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
                activatedWorldbookEntries = listOf("The city has three moons."),
                latestUserMessage = "Hello",
            ),
        )
        val second = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Follow the roleplay rules.",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Mira now prefers coffee."),
                activatedWorldbookEntries = listOf("The city has four moons."),
                latestUserMessage = "Good morning",
            ),
        )
        assertEquals(PromptRole.SYSTEM, first.first().role)
        assertTrue(first.first().content.startsWith("Follow the roleplay rules."))
        assertEquals(1, first.count { it.role == PromptRole.SYSTEM })
        assertEquals(1, second.count { it.role == PromptRole.SYSTEM })
    }

    @Test
    fun assemblesRoleCardThenWorldbookThenMemoryThenHistoryAndCurrentUser() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Role rules",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Fact A"),
                activatedWorldbookEntries = listOf("World B"),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "Earlier"),
                    PromptMessage(PromptRole.ASSISTANT, "Earlier reply"),
                ),
                latestUserMessage = "Latest",
            ),
        )
        assertEquals(1, messages.count { it.role == PromptRole.SYSTEM })
        val system = messages[0].content
        assertTrue(system.indexOf("Character: Mira") < system.indexOf("Activated worldbook entries"))
        assertTrue(system.indexOf("Activated worldbook entries") < system.indexOf("Relevant memory facts"))
        assertTrue(system.indexOf("Relevant memory facts") > system.indexOf("Activated worldbook entries"))
        assertEquals(PromptRole.USER, messages[1].role)
        assertEquals("Earlier", messages[1].content)
        assertEquals(PromptRole.ASSISTANT, messages[2].role)
        assertEquals("Earlier reply", messages[2].content)
        assertEquals(PromptRole.USER, messages.last().role)
        assertEquals("Latest", messages.last().content)
    }

    @Test
    fun blankDynamicEntriesAreFilteredWithoutCreatingEmptyMessages() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                activeMemoryFacts = listOf("", "  "),
                activatedWorldbookEntries = listOf(""),
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
