package com.mistbell.tavern.v3.feature.worldbook

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WorldBookLibraryStoreTest {
    @Test
    fun ordinaryBeforePromptAtDepthZeroRemainsPositionZero() {
        val book = WorldBook(
            id = "11111111-1111-1111-1111-111111111111",
            name = "Ordinary book",
            entries = listOf(
                WorldBookEntry(
                    id = "ordinary",
                    bookId = "11111111-1111-1111-1111-111111111111",
                    content = "before prompt",
                    insertPosition = WorldBookPlacement.POSITION_BEFORE_PROMPT,
                    depth = 0,
                ),
            ),
        )

        val exported = WorldBookLibraryStore(File("unused")).export(book)
        val entryJson = JSONObject(exported).getJSONObject("entries").getJSONObject("ordinary")
        assertEquals(0, entryJson.getInt("position"))
        val imported = WorldBookParser.parse(exported, "fallback")
        assertNotNull(imported)
        assertEquals(WorldBookPlacement.POSITION_BEFORE_PROMPT, imported!!.entries.single().insertPosition)
    }

    @Test
    fun depthZeroExportRoundTripsWithSillyTavernPositionFour() {
        val book = WorldBook(
            id = "11111111-1111-1111-1111-111111111111",
            name = "Depth book",
            entries = listOf(
                WorldBookEntry(
                    id = "depth-zero",
                    bookId = "11111111-1111-1111-1111-111111111111",
                    content = "insert at depth zero",
                    insertPosition = WorldBookPlacement.POSITION_DEPTH,
                    depth = 0,
                    depthRole = WorldBookPlacement.DEPTH_ROLE_ASSISTANT,
                ),
            ),
        )

        val exported = WorldBookLibraryStore(File("unused")).export(book)
        val json = JSONObject(exported)
        val entryJson = json.getJSONObject("entries").getJSONObject("depth-zero")
        assertEquals(4, entryJson.getInt("position"))
        assertEquals(0, entryJson.getInt("depth"))
        assertEquals(2, entryJson.getInt("role"))

        val imported = WorldBookParser.parse(exported, "fallback")
        assertNotNull(imported)
        assertEquals(book.id, imported!!.id)
        assertEquals(WorldBookPlacement.POSITION_DEPTH, imported.entries.single().insertPosition)
        assertEquals(0, imported.entries.single().depth)
        assertEquals(WorldBookPlacement.DEPTH_ROLE_ASSISTANT, imported.entries.single().depthRole)
    }
}
