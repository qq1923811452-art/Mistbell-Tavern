package com.mistbell.tavern.v3.feature.worldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookFormatTest {
    @Test
    fun canonicalCodecRoundTripsAndKeepsEntryOrder() {
        val source = WorldbookDocument(
            entries = listOf(
                WorldbookEntry("constant", "Always", "Always included", constant = true),
                WorldbookEntry("keyword", "City", "Three moons", keys = listOf("moon", "lunar")),
            ),
        )

        val encoded = WorldbookCodec.encode(source)

        assertEquals(source, WorldbookCodec.decode(encoded))
        assertEquals(encoded, WorldbookCodec.encode(WorldbookCodec.decode(encoded)))
    }

    @Test
    fun legacyConstantEntryWithoutKeywordsIsPreservedAndAlwaysActivated() {
        val imported = LegacyWorldbookImporter.import(
            """{"entries":{"12":{"uid":12,"comment":"Always","content":"Keep this lore.","constant":true,"key":[],"enabled":true}}}""",
        )

        assertEquals(1, imported.entries.size)
        assertTrue(imported.entries.single().constant)
        assertTrue(imported.entries.single().keys.isEmpty())
        assertEquals(listOf("12"), WorldbookSelector.activate(imported, "unrelated message").map { it.id })
    }

    @Test
    fun keywordActivationIsCaseInsensitiveAndDisabledEntriesStayOut() {
        val document = WorldbookDocument(
            entries = listOf(
                WorldbookEntry("a", "City", "The city lore", keys = listOf("New Avalon")),
                WorldbookEntry("b", "Disabled", "Should not appear", keys = listOf("Avalon"), enabled = false),
                WorldbookEntry("c", "Other", "No trigger", keys = listOf("desert")),
            ),
        )

        val selected = WorldbookSelector.activate(document, "We arrive in NEW AVALON.")

        assertEquals(listOf("a"), selected.map { it.id })
    }

    @Test
    fun recursiveActivationIsBoundedAndCycleSafe() {
        val document = WorldbookDocument(
            entries = listOf(
                WorldbookEntry("a", "First", "This mentions key-b", keys = listOf("start"), recursive = true),
                WorldbookEntry("b", "Second", "This mentions key-a", keys = listOf("key-b"), recursive = true),
                WorldbookEntry("c", "Third", "No activation", keys = listOf("never")),
            ),
        )

        val selected = WorldbookSelector.activate(document, "start", maxRecursiveDepth = 2)

        assertEquals(listOf("a", "b"), selected.map { it.id })
        assertFalse(selected.any { it.id == "c" })
    }

    @Test
    fun selectiveEntryRequiresBothPrimaryAndSecondaryKeywords() {
        val document = WorldbookDocument(
            entries = listOf(
                WorldbookEntry(
                    id = "selective",
                    title = "Selective",
                    content = "Secret lore",
                    keys = listOf("castle"),
                    secondaryKeys = listOf("night"),
                    requireSecondaryMatch = true,
                ),
            ),
        )

        assertTrue(WorldbookSelector.activate(document, "The castle is bright").isEmpty())
        assertEquals(1, WorldbookSelector.activate(document, "At night the castle is quiet").size)
    }
}
