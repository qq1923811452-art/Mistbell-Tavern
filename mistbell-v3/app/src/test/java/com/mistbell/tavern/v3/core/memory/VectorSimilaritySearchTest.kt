package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSimilaritySearchTest {
    private val snapshot = VectorSnapshot(
        modelId = "embedding-test",
        dimensions = 2,
        records = listOf(
            vector("same-direction", listOf(1f, 0f)),
            vector("diagonal", listOf(1f, 1f)),
            vector("opposite", listOf(-1f, 0f)),
            vector("zero", listOf(0f, 0f)),
            vector("same-direction-2", listOf(2f, 0f)),
        ),
    )

    @Test
    fun ranksByCosineSimilarityAndUsesStableIdTieBreak() {
        val hits = VectorSimilaritySearch.search(snapshot, query = listOf(1f, 0f))

        assertEquals(
            listOf("same-direction", "same-direction-2", "diagonal"),
            hits.take(3).map(VectorSearchHit::id),
        )
        assertEquals(1.0, hits[0].score, 0.000001)
        assertEquals(1.0, hits[1].score, 0.000001)
        assertTrue(hits.none { it.id == "zero" })
    }

    @Test
    fun appliesMinimumScoreAndResultLimit() {
        val hits = VectorSimilaritySearch.search(
            snapshot,
            query = listOf(1f, 0f),
            limit = 1,
            minimumScore = 0.8,
        )

        assertEquals(1, hits.size)
        assertEquals("same-direction", hits.single().id)
    }

    @Test
    fun rejectsDimensionMismatchNonFiniteAndZeroQueries() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorSimilaritySearch.search(snapshot, query = listOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorSimilaritySearch.search(snapshot, query = listOf(Float.NaN, 0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorSimilaritySearch.search(snapshot, query = listOf(0f, 0f))
        }
    }

    @Test
    fun rejectsInvalidLimitsAndThresholds() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorSimilaritySearch.search(snapshot, query = listOf(1f, 0f), limit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorSimilaritySearch.search(snapshot, query = listOf(1f, 0f), minimumScore = 2.0)
        }
    }

    private fun vector(id: String, values: List<Float>) = StoredVector(
        id = id,
        sourceTextSha256 = "a".repeat(64),
        createdAtEpochMillis = 1L,
        values = values,
    )
}
