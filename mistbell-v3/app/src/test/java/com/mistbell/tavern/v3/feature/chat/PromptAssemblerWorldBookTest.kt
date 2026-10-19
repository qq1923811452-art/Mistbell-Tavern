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
                stableCharacterDefinition = "character",
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
        assertTrue(system.indexOf("before-lore") < system.indexOf("character"))
        assertTrue(system.indexOf("character") < system.indexOf("after-lore"))
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
                worldbookEntries = listOf(WorldBookEntry("depth", "book", content = "injected", depth = 1)),
                latestUserMessage = "now",
            ),
        )
        assertEquals(listOf("stable", "one", "injected", "two", "now"), messages.map { it.content })
    }
}
