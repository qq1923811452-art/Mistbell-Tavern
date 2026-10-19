package com.mistbell.tavern.v3.feature.worldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookParserTest {
    @Test fun parsesUidMapAndHonorsEnabledAndInsertionOrder() {
        val book = WorldBookParser.parse(
            """{"name":"Lore","entries":{"1":{"comment":"rule","content":"night","key":"night","enabled":true,"insertion_order":2},"2":{"content":"hidden","disable":true,"order":8}}}""",
            "fallback",
        )
        assertNotNull(book)
        assertEquals("Lore", book!!.name)
        assertEquals(2, book.entries.size)
        assertEquals(listOf("night"), book.entries.first { it.id == "1" }.keys)
        assertEquals(false, book.entries.first { it.id == "1" }.disable)
        assertEquals(true, book.entries.first { it.id == "2" }.disable)
        assertEquals(2, book.entries.first { it.id == "1" }.order)
    }

    @Test fun parsesArrayAndSillyTavernPlacementProbabilityAndRole() {
        val book = WorldBookParser.parse(
            """{"entries":[{"uid":5,"content":"at depth","key":["dragon"],"extensions":{"position":4,"depth":3,"probability":50,"role":2}}]}""",
            "array-book",
        )
        assertNotNull(book)
        val entry = book!!.entries.single()
        assertEquals("5", entry.id)
        assertEquals(3, entry.depth)
        assertEquals(0.5, entry.probability, 0.00001)
        assertEquals(WorldBookPlacement.DEPTH_ROLE_ASSISTANT, entry.depthRole)
    }

    @Test fun rejectsMalformedOrMissingEntries() {
        assertNull(WorldBookParser.parse("not-json", "fallback"))
        assertNull(WorldBookParser.parse("""{"name":"empty"}""", "fallback"))
    }

    @Test fun missingNameUsesFallbackAndConstantEntriesActivateWithoutKeywords() {
        val book = WorldBookParser.parse("""{"entries":{"1":{"constant":true,"content":"always"}}}""", "fallback")
        assertNotNull(book)
        assertEquals("fallback", book!!.name)
        val activated = LocalWorldBookService().activateEntries(book.entries, "no match")
        assertEquals(1, activated.size)
        assertTrue(activated.single().entry.constant)
    }
}
