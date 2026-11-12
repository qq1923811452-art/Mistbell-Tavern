package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class VectorSimilaritySearchNormTest {
    @Test
    fun precomputedNormsProduceTheSameScoresAsADirectCosine() {
        val records =
            listOf(
                StoredVector("a", HASH, 0L, listOf(1f, 0f, 0f)),
                StoredVector("b", HASH, 0L, listOf(1f, 1f, 0f)),
                StoredVector("c", HASH, 0L, listOf(0f, 0f, 2f)),
            )
        val snapshot = VectorSnapshot("model", 3, records)
        val query = listOf(1f, 1f, 0f)
        val hits = VectorSimilaritySearch.search(snapshot, query, limit = 3, minimumScore = 0.0)
        val expected =
            records.associate { record ->
                record.id to cosine(query, record.values)
            }
        hits.forEach { hit ->
            assertEquals(expected.getValue(hit.id), hit.score, 1e-9)
        }
        assertEquals(listOf("b", "a", "c"), hits.map { it.id })
    }

    @Test
    fun zeroVectorIsSkippedAndLimitIsRespected() {
        val records =
            listOf(
                StoredVector("zero", HASH, 0L, listOf(0f, 0f, 0f)),
                StoredVector("a", HASH, 0L, listOf(1f, 0f, 0f)),
                StoredVector("b", HASH, 0L, listOf(0.5f, 0f, 0f)),
            )
        val hits = VectorSimilaritySearch.search(VectorSnapshot("m", 3, records), listOf(1f, 0f, 0f), limit = 1)
        assertEquals(1, hits.size)
        assertEquals("a", hits.single().id)
    }

    private fun cosine(
        a: List<Float>,
        b: List<Float>,
    ): Double {
        var dot = 0.0
        var left = 0.0
        var right = 0.0
        a.indices.forEach { index ->
            dot += a[index].toDouble() * b[index].toDouble()
            left += a[index].toDouble() * a[index].toDouble()
            right += b[index].toDouble() * b[index].toDouble()
        }
        return (dot / (sqrt(left) * sqrt(right))).coerceIn(-1.0, 1.0)
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
