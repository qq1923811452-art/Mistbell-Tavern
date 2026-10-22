package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry
import com.mistbell.tavern.v3.feature.worldbook.WorldBookPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {

    private fun entry(
        id: String,
        content: String,
        constant: Boolean = false,
        position: String = WorldBookPlacement.POSITION_AFTER_PROMPT,
    ) = WorldBookEntry(
        id = id,
        bookId = "book",
        content = content,
        constant = constant,
        insertPosition = position,
    )

    @Test
    fun systemMessageStaysStableWhenVolatileContextChanges() {
        val first = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Follow the roleplay rules.",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Mira likes tea."),
                worldbookEntries = listOf(entry("city", "The city has three moons.", constant = true)),
                latestUserMessage = "Hello",
            ),
        )
        val second = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Follow the roleplay rules.",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Mira now prefers coffee."),
                worldbookEntries = listOf(entry("city", "The city has three moons.", constant = true)),
                latestUserMessage = "Good morning",
            ),
        )
        assertEquals(PromptRole.SYSTEM, first.first().role)
        assertTrue(first.first().content.startsWith("Follow the roleplay rules."))
        assertEquals(1, first.count { it.role == PromptRole.SYSTEM })
        assertEquals(first.first().content, second.first().content)
        assertTrue(first.last().content.contains("Mira likes tea."))
        assertTrue(second.last().content.contains("Mira now prefers coffee."))
    }

    @Test
    fun volatileSectionsTravelWithTheLatestUserMessage() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Role rules",
                stableCharacterDefinition = "Character: Mira",
                activeMemoryFacts = listOf("Fact A"),
                earlierConversationSummary = "Mira is waiting at the station.",
                worldbookEntries = listOf(entry("world", "World B")),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "Earlier"),
                    PromptMessage(PromptRole.ASSISTANT, "Earlier reply"),
                ),
                latestUserMessage = "Latest",
            ),
        )
        assertEquals(1, messages.count { it.role == PromptRole.SYSTEM })
        val system = messages[0].content
        assertTrue(system.contains("Character: Mira"))
        assertFalse(system.contains("Fact A"))
        assertEquals("Earlier", messages[1].content)
        assertEquals("Earlier reply", messages[2].content)
        val tail = messages.last()
        assertEquals(PromptRole.USER, tail.role)
        assertTrue(tail.content.contains("Earlier conversation notes"))
        assertTrue(tail.content.contains("World B"))
        assertTrue(tail.content.contains("Fact A"))
        assertTrue(tail.content.endsWith("Latest"))
    }

    @Test
    fun constantWorldbookStaysInTheSystemMessage() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                worldbookEntries = listOf(entry("const", "Constant lore.", constant = true)),
                latestUserMessage = "Hi",
            ),
        )
        assertTrue(messages.first().content.contains("Constant lore."))
        assertEquals("Hi", messages.last().content)
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
        assertEquals("Hi", messages.last().content)
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
