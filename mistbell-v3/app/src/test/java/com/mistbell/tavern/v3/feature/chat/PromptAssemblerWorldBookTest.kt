package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry
import com.mistbell.tavern.v3.feature.worldbook.WorldBookPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerWorldBookTest {
    @Test fun stablePrefixPrecedesVariableWorldbookAndMemory() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "stable-prefix",
                stableCharacterDefinition = "character-definition-marker",
                activeMemoryFacts = listOf("fact"),
                worldbookEntries = listOf(
                    WorldBookEntry("before", "book", content = "before-lore"),
                    WorldBookEntry("after", "book", content = "after-lore",
                        insertPosition = WorldBookPlacement.POSITION_AFTER_PROMPT),
                ),
                latestUserMessage = "hello",
            ),
        )
        val system = messages.first().content
        assertTrue(system.indexOf("stable-prefix") < system.indexOf("before-lore"))
        assertTrue(system.indexOf("before-lore") < system.indexOf("character-definition-marker"))
        assertTrue(system.indexOf("character-definition-marker") < system.indexOf("after-lore"))
        assertTrue(system.indexOf("after-lore") < system.indexOf("fact"))
    }

    @Test fun depthEntryIsInsertedBeforeTheConfiguredRecentHistoryMessage() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "stable",
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "one"),
                    PromptMessage(PromptRole.ASSISTANT, "two"),
                ),
                worldbookEntries = listOf(WorldBookEntry("depth", "book", content = "injected", depth = 1,
                    depthRole = WorldBookPlacement.DEPTH_ROLE_ASSISTANT)),
                latestUserMessage = "now",
            ),
        )
        assertEquals(listOf("stable", "one", "injected", "two", "now"), messages.map { it.content })
    }

    @Test fun depthInjectionUsesTheBudgetedRecentHistoryWindow() {
        val budgetedHistory = PromptHistoryBudget.select(
            history = listOf(
                PromptMessage(PromptRole.USER, "old-turn"),
                PromptMessage(PromptRole.ASSISTANT, "old-answer"),
                PromptMessage(PromptRole.USER, "recent-turn"),
                PromptMessage(PromptRole.ASSISTANT, "recent-answer"),
            ),
            maxCharacters = 24,
            maxMessages = 2,
        )
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "stable",
                worldbookEntries = listOf(
                    WorldBookEntry(
                        id = "depth",
                        bookId = "book",
                        content = "depth-marker",
                        depth = 1,
                        depthRole = WorldBookPlacement.DEPTH_ROLE_ASSISTANT,
                    ),
                ),
                conversationHistory = budgetedHistory,
                latestUserMessage = "latest-user-marker",
            ),
        )

        assertEquals(
            listOf("stable", "recent-turn", "depth-marker", "recent-answer", "latest-user-marker"),
            messages.map { it.content },
        )
    }

    @Test fun historyBudgetDoesNotRemoveLatestUserMessageOrWorldbook() {
        val budgetedHistory = PromptHistoryBudget.select(
            history = listOf(
                PromptMessage(PromptRole.USER, "older"),
                PromptMessage(PromptRole.ASSISTANT, "newer"),
            ),
            maxCharacters = 5,
            maxMessages = 2,
        )
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "stable",
                worldbookEntries = listOf(WorldBookEntry("lore", "book", content = "worldbook-marker")),
                conversationHistory = budgetedHistory,
                latestUserMessage = "current-user-marker",
            ),
        )

        assertTrue(messages.first().content.contains("worldbook-marker"))
        assertEquals("current-user-marker", messages.last().content)
        assertEquals(PromptRole.USER, messages.last().role)
    }

    @Test fun systemRoleDepthEntryDoesNotInsertSystemMessageMidConversation() {
        val messages = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "stable",
                conversationHistory = listOf(
                    PromptMessage(PromptRole.USER, "one"),
                    PromptMessage(PromptRole.ASSISTANT, "two"),
                ),
                worldbookEntries = listOf(WorldBookEntry(
                    "system-depth", "book", content = "system-injected", depth = 1,
                    depthRole = WorldBookPlacement.DEPTH_ROLE_SYSTEM,
                )),
                latestUserMessage = "now",
            ),
        )

        assertEquals(1, messages.count { it.role == PromptRole.SYSTEM })
        assertTrue(messages.first().content.contains("[depth 1] system-injected"))
        assertEquals(listOf(PromptRole.SYSTEM, PromptRole.USER, PromptRole.ASSISTANT, PromptRole.USER), messages.map { it.role })
    }
}
