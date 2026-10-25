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
    fun userDepthEntryKeepsItsHistoricalPositionAndRole() {
        val depthEntry = WorldBookEntry(
            id = "depth",
            bookId = "book",
            content = "Remember this",
            insertPosition = WorldBookPlacement.POSITION_DEPTH,
            depth = 1,
            depthRole = WorldBookPlacement.DEPTH_ROLE_USER,
        )
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Rules",
                worldbookEntries = listOf(depthEntry),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "one"),
                    PromptMessage(PromptRole.ASSISTANT, "two"),
                    PromptMessage(PromptRole.USER, "three"),
                ),
                latestUserMessage = "four",
            ),
        )
        val depthMessage = messages.first { it.content == "Remember this" }
        assertEquals(PromptRole.USER, depthMessage.role)
        assertEquals("three", messages[messages.indexOf(depthMessage) + 1].content)
    }

    @Test
    fun assistantDepthEntryKeepsAssistantRole() {
        val depthEntry = WorldBookEntry(
            id = "depth-assistant",
            bookId = "book",
            content = "Earlier assistant aside",
            insertPosition = WorldBookPlacement.POSITION_DEPTH,
            depth = 1,
            depthRole = WorldBookPlacement.DEPTH_ROLE_ASSISTANT,
        )
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Rules",
                worldbookEntries = listOf(depthEntry),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "one"),
                    PromptMessage(PromptRole.ASSISTANT, "two"),
                ),
                latestUserMessage = "three",
            ),
        )
        val depthMessage = messages.first { it.content == "Earlier assistant aside" }
        assertEquals(PromptRole.ASSISTANT, depthMessage.role)
        assertEquals("two", messages[messages.indexOf(depthMessage) + 1].content)
    }

    @Test
    fun depthBeyondHistoryClampsToTheStart() {
        val depthEntry = WorldBookEntry(
            id = "deep",
            bookId = "book",
            content = "Very old note",
            insertPosition = WorldBookPlacement.POSITION_DEPTH,
            depth = 99,
            depthRole = WorldBookPlacement.DEPTH_ROLE_USER,
        )
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Rules",
                worldbookEntries = listOf(depthEntry),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "one"),
                    PromptMessage(PromptRole.ASSISTANT, "two"),
                ),
                latestUserMessage = "three",
            ),
        )
        assertEquals("Very old note", messages[1].content)
        assertEquals("one", messages[2].content)
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
    fun constantEntriesOutsideTheSystemPrefixAreStillInjectedExactlyOnce() {
        val entries = listOf(
            entry(
                "an-before",
                "Constant author note.",
                constant = true,
                position = WorldBookPlacement.POSITION_AN_BEFORE,
            ),
            entry(
                "an-after",
                "Constant author note after.",
                constant = true,
                position = WorldBookPlacement.POSITION_AN_AFTER,
            ),
            entry(
                "em-before",
                "Constant example before.",
                constant = true,
                position = WorldBookPlacement.POSITION_EM_BEFORE,
            ),
            entry(
                "em-after",
                "Constant example after.",
                constant = true,
                position = WorldBookPlacement.POSITION_EM_AFTER,
            ),
        )

        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                worldbookEntries = entries,
                latestUserMessage = "Hi",
            ),
        )

        val joined = messages.joinToString("\n") { it.content }
        entries.forEach { entry ->
            assertEquals(
                "Entry ${entry.id} must be injected exactly once.",
                1,
                Regex(Regex.escape(entry.content)).findAll(joined).count(),
            )
        }
    }

    @Test
    fun constantSystemDepthEntryIsInjectedExactlyOnce() {
        val depthEntry = WorldBookEntry(
            id = "depth-constant",
            bookId = "book",
            content = "Constant depth lore.",
            constant = true,
            insertPosition = WorldBookPlacement.POSITION_DEPTH,
            depth = 2,
            depthRole = WorldBookPlacement.DEPTH_ROLE_SYSTEM,
        )

        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                worldbookEntries = listOf(depthEntry),
                latestUserMessage = "Hi",
            ),
        )

        assertEquals(1, messages.count { it.content.contains("Constant depth lore.") })
    }

    @Test
    fun postHistoryInstructionsFollowTheLatestUserMessage() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "Earlier"),
                    PromptMessage(PromptRole.ASSISTANT, "Earlier reply"),
                ),
                latestUserMessage = "Latest",
                postHistoryInstructions = "Stay in character.",
            ),
        )

        val tail = messages.last()
        assertEquals(PromptRole.USER, tail.role)
        assertTrue(tail.content.startsWith("Latest"))
        assertTrue(tail.content.endsWith("Post-history instructions:\nStay in character."))
    }

    @Test
    fun constantEntriesOutsideTheSystemPrefixStayInTheDynamicContext() {
        val entries = listOf(
            entry(
                "an-before",
                "Constant author note.",
                constant = true,
                position = WorldBookPlacement.POSITION_AN_BEFORE,
            ),
            entry(
                "em-after",
                "Constant example after.",
                constant = true,
                position = WorldBookPlacement.POSITION_EM_AFTER,
            ),
        )

        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                worldbookEntries = entries,
                latestUserMessage = "Hi",
            ),
        )

        val system = messages.first()
        val tail = messages.last()
        assertEquals(PromptRole.SYSTEM, system.role)
        assertEquals(PromptRole.USER, tail.role)
        entries.forEach { entry ->
            assertTrue(
                "Before/after-system constants must not leak into the stable prefix.",
                !system.content.contains(entry.content),
            )
            assertEquals(1, tail.content.split(entry.content).size - 1)
        }
    }

    @Test
    fun disabledConstantEntryIsNotInjected() {
        val disabled = WorldBookEntry(
            id = "disabled",
            bookId = "book",
            content = "Disabled lore.",
            constant = true,
            disable = true,
        )

        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                worldbookEntries = listOf(disabled),
                latestUserMessage = "Hi",
            ),
        )

        assertTrue(messages.none { it.content.contains("Disabled lore.") })
    }

    @Test
    fun depthAssistantInsertionIsMarkedSynthetic() {
        val depthEntry = WorldBookEntry(
            id = "depth-assistant",
            bookId = "book",
            content = "Lore aside.",
            constant = true,
            insertPosition = WorldBookPlacement.POSITION_DEPTH,
            depth = 1,
            depthRole = WorldBookPlacement.DEPTH_ROLE_ASSISTANT,
        )

        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                worldbookEntries = listOf(depthEntry),
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "Earlier"),
                    PromptMessage(PromptRole.ASSISTANT, "Earlier reply"),
                ),
                latestUserMessage = "Hi",
            ),
        )

        val inserted = messages.single { it.content == "Lore aside." }
        assertEquals(PromptRole.ASSISTANT, inserted.role)
        assertTrue(inserted.synthetic)
        assertFalse(messages.single { it.content == "Earlier reply" }.synthetic)
    }

    @Test
    fun memoryQueryExcludesPostHistoryInstructionsButKeepsTheUserTurn() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                worldbookEntries = listOf(entry("lore", "Library lore.")),
                latestUserMessage = "Where is the library?",
                postHistoryInstructions = "Stay in character.",
            ),
        )

        val query = PromptAssembler.memoryQuery(messages)

        assertTrue(query!!.contains("Where is the library?"))
        assertTrue(query.contains("Library lore."))
        assertFalse(query.contains("Stay in character."))
    }

    @Test
    fun memoryQueryFallsBackToTheWholeUserTurnWithoutPostHistory() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, "Stable rules"),
            PromptMessage(PromptRole.USER, "Plain question"),
        )

        assertEquals("Plain question", PromptAssembler.memoryQuery(messages))
    }

    @Test
    fun blankPostHistoryInstructionsAreNotAppended() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "Stable rules",
                latestUserMessage = "Hi",
                postHistoryInstructions = "   ",
            ),
        )

        assertEquals("Hi", messages.last().content)
        assertFalse(messages.last().content.contains(PromptAssembler.POST_HISTORY_HEADER))
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
