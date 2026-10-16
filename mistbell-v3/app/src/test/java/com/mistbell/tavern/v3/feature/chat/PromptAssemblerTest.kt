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

        assertEquals(first.first(), second.first())
        assertEquals(PromptRole.SYSTEM, first.first().role)
    }

    @Test
    fun stablePrefixComesBeforeMemoryWorldbookHistoryAndLatestUser() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                activeMemoryFacts = listOf("Fact A"),
                activatedWorldbookEntries = listOf("World B"),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "Earlier"),
                    PromptMessage(PromptRole.ASSISTANT, "Earlier reply"),
                ),
                latestUserMessage = "Latest",
            ),
        )

        assertTrue(messages[0].content.startsWith("Stable rules"))
        assertTrue(messages[1].content.indexOf("Relevant memory facts") <
            messages[1].content.indexOf("Activated worldbook entries"))
        assertEquals(PromptRole.USER, messages[messages.lastIndex].role)
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
