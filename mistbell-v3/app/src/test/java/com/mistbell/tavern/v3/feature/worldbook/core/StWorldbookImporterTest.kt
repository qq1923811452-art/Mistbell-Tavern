package com.mistbell.tavern.v3.feature.worldbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StWorldbookImporterTest {

    @Test
    fun mapsDocumentedStFieldSemantics() {
        val raw = """
            {
              "name": "Test Book",
              "entries": {
                "0": {
                  "uid": 0,
                  "comment": "Depth Entry",
                  "content": "depth content",
                  "key": ["alpha", "beta"],
                  "keysecondary": ["gamma"],
                  "selective": true,
                  "position": 4,
                  "depth": 3,
                  "probability": 80,
                  "role": 1,
                  "sticky": true,
                  "cooldown": 2,
                  "delay": 1,
                  "group": "g1",
                  "order": 5
                },
                "1": {
                  "uid": 1,
                  "content": "constant content",
                  "constant": true,
                  "position": 0
                }
              }
            }
        """.trimIndent()
        val document = StWorldbookImporter.import(raw)
        assertEquals("Test Book", document.name)
        assertEquals(2, document.entries.size)

        val depth = document.entries.first { it.id == "0" }
        assertEquals(WorldbookPositions.DEPTH, depth.insertPosition)
        assertEquals(3, depth.depth)
        assertEquals(0.8, depth.probability, 0.0001)
        assertEquals(WorldbookPositions.ROLE_USER, depth.depthRole)
        assertTrue(depth.sticky)
        assertEquals(2, depth.cooldown)
        assertEquals(1, depth.delay)
        assertEquals("g1", depth.groupName)
        assertEquals(listOf("alpha", "beta"), depth.keys)
        assertEquals(listOf("gamma"), depth.secondaryKeys)
        assertTrue(depth.requireSecondaryMatch)
        assertEquals(5, depth.order)

        val constant = document.entries.first { it.id == "1" }
        assertTrue(constant.constant)
        assertEquals(WorldbookPositions.BEFORE_PROMPT, constant.insertPosition)
        assertEquals(1.0, constant.probability, 0.0001)
    }

    @Test
    fun acceptsArrayEntriesAndCommaSeparatedKeys() {
        val raw = """
            {"entries": [
              {"uid": 7, "content": "body", "key": "alpha, beta", "disable": true}
            ]}
        """.trimIndent()
        val document = StWorldbookImporter.import(raw)
        val entry = document.entries.single()
        assertEquals(listOf("alpha", "beta"), entry.keys)
        assertTrue(entry.disabled)
    }

    @Test
    fun skipsEntriesWithoutContent() {
        val raw = """{"entries": [{"uid": 1, "content": "  "}, {"uid": 2, "content": "kept"}]}"""
        val document = StWorldbookImporter.import(raw)
        assertEquals(1, document.entries.size)
        assertFalse(document.entries.single().content.isBlank())
    }
}
