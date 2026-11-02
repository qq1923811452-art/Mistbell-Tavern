package com.mistbell.tavern.v3.core.memory

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quantized path scores `v = scale * b` as `(u . b) / (|u| * |b|)` because a single positive
 * scale cancels out of a cosine. These tests pin the two properties that make that safe: identical
 * ranking versus the dequantizing path, and an unchanged stable tie-break.
 */
class VectorSimilaritySearchQuantizedTest {
    @Test
    fun quantizedScoringMatchesDequantizedScoring() {
        val snapshot = mappedSnapshot(records = 300, dimensions = 64)
        val random = java.util.Random(11L)
        repeat(20) {
            val query = List(64) { random.nextGaussian().toFloat() }
            val expected = dequantizedSearch(snapshot, query)
            val actual = VectorSimilaritySearch.search(snapshot, query, limit = 20, minimumScore = 0.0)
            assertEquals(expected.map(VectorSearchHit::id), actual.map(VectorSearchHit::id))
            expected.zip(actual).forEach { (left, right) ->
                assertTrue(
                    "score drift ${abs(left.score - right.score)}",
                    abs(left.score - right.score) < 1e-6,
                )
            }
        }
    }

    @Test
    fun quantizedPathKeepsTheStableIdTieBreakAndSkipsZeroVectors() {
        val records = listOf(
            StoredVector("zero", HASH, 1L, List(4) { 0f }),
            StoredVector("alpha", HASH, 2L, listOf(1f, 0f, 0f, 0f)),
            StoredVector("alpha-twin", HASH, 3L, listOf(2f, 0f, 0f, 0f)),
            StoredVector("beta", HASH, 4L, listOf(0f, 1f, 0f, 0f)),
        )
        val snapshot = mappedSnapshot(records)
        val hits = VectorSimilaritySearch.search(snapshot, listOf(1f, 0f, 0f, 0f), limit = 4, minimumScore = 0.0)

        assertEquals(listOf("alpha", "alpha-twin", "beta"), hits.map(VectorSearchHit::id))
        assertEquals(1.0, hits[0].score, 1e-9)
        assertEquals(1.0, hits[1].score, 1e-9)
        assertTrue(hits.none { it.id == "zero" })
    }

    @Test
    fun boundedSelectionReturnsTheSamePrefixAsAFullSort() {
        val snapshot = mappedSnapshot(records = 500, dimensions = 32)
        val query = List(32) { (it % 7).toFloat() - 3f }
        val full = VectorSimilaritySearch.search(snapshot, query, limit = 100, minimumScore = -1.0)
        listOf(1, 2, 5, 10, 100).forEach { limit ->
            val limited = VectorSimilaritySearch.search(snapshot, query, limit = limit, minimumScore = -1.0)
            assertEquals(limit, limited.size)
            assertEquals(full.take(limit).map(VectorSearchHit::id), limited.map(VectorSearchHit::id))
        }
    }

    @Test
    fun minimumScoreIsAppliedBeforeSelection() {
        val snapshot = mappedSnapshot(records = 120, dimensions = 24)
        val query = List(24) { 1f }
        val hits = VectorSimilaritySearch.search(snapshot, query, limit = 100, minimumScore = 0.9)
        assertTrue(hits.all { it.score >= 0.9 })
        assertTrue(hits.size <= 100)
    }

    /** Same algorithm as before the change, kept here as the reference for equivalence. */
    private fun dequantizedSearch(snapshot: VectorSnapshot, query: List<Float>): List<VectorSearchHit> {
        val queryValues = query.toFloatArray()
        var querySquared = 0.0
        for (value in queryValues) querySquared += value.toDouble() * value.toDouble()
        val queryNorm = sqrt(querySquared)
        val candidateValues = FloatArray(snapshot.dimensions)
        val norms = snapshot.norms
        return snapshot.records.asSequence().mapIndexedNotNull { index, record ->
                (record.values as FloatVectorView).fillInto(candidateValues)
                val recordNorm = norms[index]
                if (recordNorm == 0.0) return@mapIndexedNotNull null
                var dot = 0.0
                for (position in queryValues.indices) {
                    dot += queryValues[position].toDouble() * candidateValues[position].toDouble()
                }
                val score = (dot / (queryNorm * recordNorm)).coerceIn(-1.0, 1.0)
                VectorSearchHit(record.id, score, record.sourceTextSha256, record.createdAtEpochMillis)
            }
            .sortedWith(compareByDescending<VectorSearchHit> { it.score }.thenBy { it.id })
            .take(20)
            .toList()
    }

    private fun mappedSnapshot(records: List<StoredVector>): VectorSnapshot =
        VectorSnapshotCodec.decodeMapped(ByteBuffer.wrap(VectorSnapshotCodec.encode(VectorSnapshot(MODEL, 4, records))))

    private fun mappedSnapshot(records: Int, dimensions: Int): VectorSnapshot {
        val random = java.util.Random(5L)
        val stored = (0 until records).map { index ->
            StoredVector(
                id = "rec-%04d".format(index),
                sourceTextSha256 = HASH,
                createdAtEpochMillis = index.toLong(),
                values = FloatArray(dimensions) { random.nextGaussian().toFloat() }.asList(),
            )
        }
        val encoded = VectorSnapshotCodec.encode(VectorSnapshot(MODEL, dimensions, stored))
        return VectorSnapshotCodec.decodeMapped(ByteBuffer.wrap(encoded))
    }

    private companion object {
        const val MODEL = "embeddinggemma-2"
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
