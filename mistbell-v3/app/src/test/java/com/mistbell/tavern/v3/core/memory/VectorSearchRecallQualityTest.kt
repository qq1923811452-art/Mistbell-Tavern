package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Answers the question an equivalence test cannot: does the stored int8 form still surface the
 * neighbours an exact float search would return? Equivalence only proves the new scorer matches
 * the old one; neither establishes relevance on real text queries.
 *
 * Ground truth is computed over the pre-quantization float vectors, so any gap is caused by the
 * snapshot format itself and would also affect the previous dequantizing implementation.
 */
class VectorSearchRecallQualityTest {
    @Test
    fun int8SnapshotKeepsGroundTruthTopFiveNeighbours() {
        val vectors = clusteredVectors()
        val snapshot = mappedSnapshot(vectors)
        val random = java.util.Random(5L)
        var recallAtFive = 0.0
        var rankOneMatches = 0
        var maxScoreError = 0.0

        repeat(QUERY_COUNT) { index ->
            val query = noisyQuery(vectors[index % vectors.size], random)
            val truth = exactTopK(vectors, query, TOP_K)
            val hits = VectorSimilaritySearch.search(snapshot, query.toList(), TOP_K, minimumScore = -1.0)

            val found = hits.map { it.id.removePrefix(RECORD_PREFIX).toInt() }
            assertEquals(TOP_K, found.size)
            recallAtFive += found.intersect(truth.toSet()).size / TOP_K.toDouble()
            if (found.first() == truth.first()) rankOneMatches++
            maxScoreError = maxOf(maxScoreError, abs(hits.first().score - exactCosine(vectors[found.first()], query)))
        }

        assertTrue(
            "mean recall@$TOP_K was ${recallAtFive / QUERY_COUNT}",
            recallAtFive / QUERY_COUNT >= MINIMUM_RECALL,
        )
        assertTrue(
            "rank-1 agreement was $rankOneMatches/$QUERY_COUNT",
            rankOneMatches >= (QUERY_COUNT * MINIMUM_RANK_ONE_AGREEMENT).toInt(),
        )
        assertTrue("max top-1 score error was $maxScoreError", maxScoreError < MAXIMUM_SCORE_ERROR)
    }

    @Test
    fun repeatedSearchesProduceStableScores() {
        val vectors = clusteredVectors()
        val snapshot = mappedSnapshot(vectors)
        val query = noisyQuery(vectors[7], java.util.Random(3L)).toList()

        val first = VectorSimilaritySearch.search(snapshot, query, TOP_K, minimumScore = -1.0)
        val second = VectorSimilaritySearch.search(snapshot, query, TOP_K, minimumScore = -1.0)

        // This checks determinism only; recomputing norms would also pass. It is not a cache-hit test.
        assertEquals(first.map(VectorSearchHit::id), second.map(VectorSearchHit::id))
        first.zip(second).forEach { (left, right) -> assertEquals(left.score, right.score, 0.0) }
    }

    /** Clustered neighbours make the top-k non-trivial: many records are close, so ties matter. */
    private fun clusteredVectors(): List<FloatArray> {
        val random = java.util.Random(21L)
        val centers = List(CLUSTER_COUNT) { normalize(FloatArray(DIMENSIONS) { random.nextGaussian().toFloat() }) }
        return List(CLUSTER_COUNT * RECORDS_PER_CLUSTER) { index ->
            val center = centers[index / RECORDS_PER_CLUSTER]
            normalize(
                FloatArray(DIMENSIONS) { position ->
                    (center[position] + (random.nextGaussian() * (0.55 / sqrt(DIMENSIONS.toDouble()))).toFloat())
                },
            )
        }
    }

    private fun noisyQuery(
        base: FloatArray,
        random: java.util.Random,
    ): FloatArray =
        normalize(
            FloatArray(DIMENSIONS) { position ->
                (base[position] + (random.nextGaussian() * 0.12).toFloat())
            },
        )

    private fun normalize(vector: FloatArray): FloatArray {
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value.toDouble()
        val norm = sqrt(squared)
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    private fun exactCosine(
        left: FloatArray,
        right: FloatArray,
    ): Double {
        var dot = 0.0
        var leftSquared = 0.0
        var rightSquared = 0.0
        for (position in left.indices) {
            val l = left[position].toDouble()
            val r = right[position].toDouble()
            dot += l * r
            leftSquared += l * l
            rightSquared += r * r
        }
        return dot / sqrt(leftSquared * rightSquared)
    }

    private fun exactTopK(
        vectors: List<FloatArray>,
        query: FloatArray,
        k: Int,
    ): List<Int> = vectors.indices.sortedWith(compareByDescending<Int> { exactCosine(vectors[it], query) }.thenBy { it }).take(k)

    private fun mappedSnapshot(vectors: List<FloatArray>): VectorSnapshot {
        val records =
            vectors.mapIndexed { index, vector ->
                StoredVector(
                    id = "$RECORD_PREFIX%04d".format(index),
                    sourceTextSha256 = HASH,
                    createdAtEpochMillis = index.toLong(),
                    values = vector.toList(),
                )
            }
        val encoded = VectorSnapshotCodec.encode(VectorSnapshot(MODEL, DIMENSIONS, records))
        return VectorSnapshotCodec.decodeMapped(ByteBuffer.wrap(encoded))
    }

    private companion object {
        const val MODEL = "google/embeddinggemma-2"
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val RECORD_PREFIX = "rec-"
        const val DIMENSIONS = 768
        const val CLUSTER_COUNT = 20
        const val RECORDS_PER_CLUSTER = 20
        const val QUERY_COUNT = 100
        const val TOP_K = 5

        /** Synthetic storage-neighbour regression bounds, not semantic recall claims. */
        const val MINIMUM_RECALL = 0.95
        const val MINIMUM_RANK_ONE_AGREEMENT = 0.99
        const val MAXIMUM_SCORE_ERROR = 2e-3
    }
}
