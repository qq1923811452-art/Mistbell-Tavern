package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroSceneChunkerTest {
    @Test
    fun emitsStableSceneRecordsForSixCompletedTurns() {
        val turns = (0 until 6).map { turn(it) }

        val first = MacroSceneChunker.chunkDialogueTurns("conversation-1", turns)
        val second = MacroSceneChunker.chunkDialogueTurns("conversation-1", turns)

        assertEquals(first, second)
        assertEquals(1, first.size)
        val record = first.single()
        assertTrue(record.id.startsWith("scene:"))
        assertTrue(record.id.endsWith(":chunk:0"))
        assertTrue(record.text.startsWith("[剧情脉络] "))
        assertTrue(record.text.contains("\n[片段] User: user-0"))
    }

    @Test
    fun splitsLongScenesWithOneTurnOverlap() {
        val turns =
            (0 until 6).map { index ->
                MacroDialogueTurn(
                    turnId = "turn-$index",
                    userText = "u$index".repeat(150),
                    assistantText = "a$index".repeat(150),
                )
            }

        val records = MacroSceneChunker.chunkDialogueTurns("conversation-1", turns)

        assertTrue(records.size > 1)
        val firstChunk = records[0].text
        val secondChunk = records[1].text
        assertTrue(firstChunk.contains("User: u0"))
        assertTrue(firstChunk.contains("Assistant: a0"))
        assertTrue(secondChunk.contains("User: u1"))
        assertTrue(secondChunk.contains("Assistant: a1"))
    }

    @Test
    fun ignoresIncompleteTrailingScene() {
        val records = MacroSceneChunker.chunkDialogueTurns("conversation-1", (0 until 5).map { turn(it) })
        assertTrue(records.isEmpty())
    }

    private fun turn(index: Int) =
        MacroDialogueTurn(
            turnId = "turn-$index",
            userText = "user-$index",
            assistantText = "assistant-$index",
        )
}
