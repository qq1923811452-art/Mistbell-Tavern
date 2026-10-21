package com.mistbell.tavern.v3.feature.worldbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookJsonCodecTest {

    @Test
    fun roundTripPreservesEveryField() {
        val document = WorldbookDocument(
            name = "Round Trip",
            entries = listOf(
                WorldbookEntry(
                    id = "a",
                    comment = "Alpha",
                    keys = listOf("alpha", "阿尔法"),
                    secondaryKeys = listOf("beta"),
                    requireSecondaryMatch = true,
                    recursive = true,
                    content = "alpha content",
                    constant = false,
                    disabled = false,
                    order = 3,
                    insertPosition = WorldbookPositions.DEPTH,
                    depth = 2,
                    probability = 0.75,
                    depthRole = WorldbookPositions.ROLE_ASSISTANT,
                    sticky = true,
                    cooldown = 4,
                    delay = 1,
                    groupName = "grp",
                ),
            ),
        )
        val decoded = WorldbookJsonCodec.decode(WorldbookJsonCodec.encode(document))
        assertEquals(document, decoded)
    }

    @Test
    fun rejectsUnknownFormatVersion() {
        val error = runCatching {
            WorldbookJsonCodec.decode("""{"version": 99, "entries": []}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
