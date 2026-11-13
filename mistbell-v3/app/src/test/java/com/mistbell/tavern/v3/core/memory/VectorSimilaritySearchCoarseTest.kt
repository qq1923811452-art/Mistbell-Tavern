package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.sqrt

/** 128d coarse recall followed by exact 768d scoring, for legacy and byte-quantized snapshots. */
class VectorSimilaritySearchCoarseTest {
    private val query = List(DIMENSIONS) { index -> if (index < COARSE) 1f else 0f }

    @Test
    fun twoStageSearchRanksTheExactFullDimensionScores() {
        val snapshot = VectorSnapshot(MODEL, DIMENSIONS, legacyRecords())
        val hits = VectorSimilaritySearch.search(snapshot, query, limit = 5, minimumScore = 0.0)

        assertEquals(listOf("best", "decoy"), hits.map(VectorSearchHit::id))
        assertEquals(1.0, hits[0].score, 1e-6)
        assertEquals(0.8, hits[1].score, 1e-6)
    }

    @Test
    fun quantizedTwoStageSearchMatchesTheSameExpectedScores() {
        val legacy = VectorSnapshot(MODEL, DIMENSIONS, legacyRecords())
        val mapped = VectorSnapshotCodec.decodeMapped(ByteBuffer.wrap(VectorSnapshotCodec.encode(legacy)))

        val hits = VectorSimilaritySearch.search(mapped, query, limit = 5, minimumScore = 0.0)

        assertEquals(listOf("best", "decoy"), hits.map(VectorSearchHit::id))
        assertEquals(1.0, hits[0].score, 1e-3)
        assertEquals(0.8, hits[1].score, 2e-2)
    }

    private fun legacyRecords(): List<StoredVector> =
        buildList {
            add(StoredVector("best", HASH, 0L, vector(prefix = 1f, tail = 0f)))
            add(StoredVector("decoy", HASH, 1L, vector(prefix = 0.8f, tail = 0.6f * sqrt(COARSE.toFloat()))))
            repeat(COUNT - 2) { index ->
                add(StoredVector("other-%02d".format(index), HASH, index + 2L, tailOnly(index)))
            }
        }

    private fun vector(
        prefix: Float,
        tail: Float,
    ): List<Float> =
        List(DIMENSIONS) { index ->
            when {
                index < COARSE -> prefix
                index == TAIL_INDEX -> tail
                else -> 0f
            }
        }

    private fun tailOnly(index: Int): List<Float> {
        val position = TAIL_INDEX + 1 + index
        return List(DIMENSIONS) { if (it == position) 1f else 0f }
    }

    private companion object {
        const val MODEL = "embeddinggemma-2"
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIMENSIONS = 768
        const val COARSE = 128
        const val COUNT = 40
        const val TAIL_INDEX = 200
    }
}
