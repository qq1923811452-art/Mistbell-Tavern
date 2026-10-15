package com.mistbell.tavern.android.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalMemoryFactPolicyTest {
    @Test
    fun `changed single-valued fact is detected as temporal conflict`() {
        val existing =
            TemporalMemoryFactPolicy.Fact(
                id = 12L,
                subject = "user",
                relation = "name",
                objectValue = "Alice",
            )

        val conflicts =
            TemporalMemoryFactPolicy.findConflicts(
                subject = "user",
                relation = "name",
                objectValue = "Bob",
                content = "user 的名字已改为 Bob",
                existingFacts = listOf(existing),
            )

        assertEquals(listOf(12L), conflicts.map { it.id })
    }

    @Test
    fun `multi-valued relation remains additive unless update is explicit`() {
        val existing =
            TemporalMemoryFactPolicy.Fact(
                id = 13L,
                subject = "user",
                relation = "likes",
                objectValue = "tea",
            )

        assertTrue(
            TemporalMemoryFactPolicy.findConflicts(
                subject = "user",
                relation = "likes",
                objectValue = "coffee",
                content = "user likes coffee",
                existingFacts = listOf(existing),
            ).isEmpty(),
        )
        assertEquals(
            listOf(13L),
            TemporalMemoryFactPolicy.findRelatedFacts(
                subject = "user",
                relation = "likes",
                objectValue = "coffee",
                existingFacts = listOf(existing),
            ).map { it.id },
        )
        assertEquals(
            listOf(13L),
            TemporalMemoryFactPolicy.findConflicts(
                subject = "user",
                relation = "likes",
                objectValue = "coffee",
                content = "user 的偏好已改为 coffee",
                existingFacts = listOf(existing),
            ).map { it.id },
        )
    }

    @Test
    fun `historical fact gets valid-to and replacement link`() {
        val original = Json.encodeToString(mapOf("subject" to "user", "relation" to "name", "object" to "Alice"))

        val updated = TemporalMemoryFactPolicy.markHistorical(original, "2026-10-15T12:00:00Z", 20L)
        val decoded = Json.decodeFromString<Map<String, String>>(updated)

        assertEquals("historical", decoded["status"])
        assertEquals("2026-10-15T12:00:00Z", decoded["valid_to"])
        assertEquals("20", decoded["superseded_by"])
        assertEquals("Alice", decoded["object"])
    }

    @Test
    fun `temporal updates preserve non-string structured fields`() {
        val original =
            """
            {
              "subject": "user",
              "relation": "name",
              "object": "Alice",
              "confidence": 0.95,
              "aliases": ["A", "Al"],
              "extra": {"source": "chat"}
            }
            """.trimIndent()

        val updated = TemporalMemoryFactPolicy.markHistorical(original, "2026-10-15T12:00:00Z", 20L)
        val decoded = Json.parseToJsonElement(updated).jsonObject

        assertEquals("Alice", decoded["object"]?.jsonPrimitive?.content)
        assertEquals("0.95", decoded["confidence"]?.jsonPrimitive?.content)
        assertEquals("""["A","Al"]""", decoded["aliases"].toString())
        assertEquals("""{"source":"chat"}""", decoded["extra"].toString())
        assertEquals("historical", decoded["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun emptyStructuredDataDoesNotCreateLegacyRawMarker() {
        val updated = TemporalMemoryFactPolicy.markHistorical("", "2026-10-15T12:00:00Z", 20L)
        val decoded = Json.parseToJsonElement(updated).jsonObject

        assertEquals("historical", decoded["status"]?.jsonPrimitive?.content)
        assertFalse(decoded.containsKey("legacy_raw"))
    }

    @Test
    fun `malformed legacy structured data is preserved during temporal update`() {
        val updated = TemporalMemoryFactPolicy.markHistorical("not-json", "2026-10-15T12:00:00Z", 20L)
        val decoded = Json.parseToJsonElement(updated).jsonObject

        assertEquals("not-json", decoded["legacy_raw"]?.jsonPrimitive?.content)
        assertEquals("historical", decoded["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun stagedSingleValueConflictIsDetectedButSameValueIsNotAConflict() {
        val staged = TemporalMemoryFactPolicy.Fact(
            id = 1L,
            subject = "user",
            relation = "name",
            objectValue = "Alice",
        )

        assertTrue(
            TemporalMemoryFactPolicy.conflictsWithStagedFact(
                subject = "user",
                relation = "name",
                objectValue = "Bob",
                content = "user name Bob",
                stagedFact = staged,
            ),
        )
        assertFalse(
            TemporalMemoryFactPolicy.conflictsWithStagedFact(
                subject = "user",
                relation = "name",
                objectValue = "Alice",
                content = "user name Alice",
                stagedFact = staged,
            ),
        )
        assertFalse(
            TemporalMemoryFactPolicy.conflictsWithStagedFact(
                subject = "user",
                relation = "age",
                objectValue = "Bob",
                content = "user age Bob",
                stagedFact = staged,
            ),
        )
    }

    @Test
    fun `historical facts are excluded from current-fact deduplication policy`() {
        val historical =
            Json.encodeToString(
                mapOf(
                    "subject" to "user",
                    "relation" to "name",
                    "object" to "Alice",
                    "status" to "historical",
                    "valid_to" to "2026-10-15T12:00:00Z",
                ),
            )

        assertTrue(TemporalMemoryFactPolicy.isHistorical(historical))
        assertFalse(TemporalMemoryFactPolicy.isHistorical(null))
    }

    @Test
    fun `current fact stores start time and previous value metadata`() {
        val original = Json.encodeToString(mapOf("subject" to "user", "relation" to "name", "object" to "Bob"))

        val updated =
            TemporalMemoryFactPolicy.markCurrent(
                original,
                "2026-10-15T12:00:00Z",
                listOf(12L),
                listOf("Alice"),
            )
        val decoded = Json.decodeFromString<Map<String, String>>(updated)

        assertEquals("current", decoded["status"])
        assertEquals("2026-10-15T12:00:00Z", decoded["valid_from"])
        assertEquals("", decoded["valid_to"])
        assertEquals("12", decoded["supersedes_ids"])
        assertEquals("Alice", decoded["previous_values"])
    }
}
