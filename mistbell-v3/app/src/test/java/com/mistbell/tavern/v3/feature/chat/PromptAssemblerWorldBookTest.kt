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
