package com.mistbell.tavern.v3.core.memory

import java.util.PriorityQueue
import kotlin.math.sqrt

/** A semantic match with cosine similarity in the inclusive range [-1.0, 1.0]. */
data class VectorSearchHit(
    val id: String,
    val score: Double,
    val sourceTextSha256: String,
    val createdAtEpochMillis: Long,
)

/**
 * Deterministic in-memory nearest-neighbour search over a validated snapshot.
 *
 * This is deliberately a small, dependency-free baseline suitable for local memory retrieval.
 * It never embeds text itself: callers must obtain the query vector from the configured local
 * embedding runtime and validate that runtime's actual backend before searching.
 *
 * Two scoring paths exist and produce the same cosine similarity:
 *
 * - Byte-quantized snapshots are scored straight from the mapped bytes. A stored vector is
 *   `v = scale * q` with a single positive `scale`, so `cos(u, v) = (u . q) / (|u| * |q|)`; the
 *   scale cancels and neither dequantization nor a temporary `FloatArray` is needed.
 * - Legacy float32 snapshots keep the previous dequantizing path, including its finite-value
 *   checks, so archived snapshots stay readable.
 */
object VectorSimilaritySearch {
    fun search(
        snapshot: VectorSnapshot,
        query: List<Float>,
        limit: Int = DEFAULT_RESULT_LIMIT,
        minimumScore: Double = DEFAULT_MINIMUM_SCORE,
    ): List<VectorSearchHit> {
        require(query.size == snapshot.dimensions) {
            "Query vector dimension does not match the stored snapshot."
        }
        require(query.isNotEmpty() && query.all(Float::isFinite)) {
            "Query vector must contain finite values."
        }
        require(limit in 1..MAX_RESULT_LIMIT) {
            "Result limit must be between 1 and $MAX_RESULT_LIMIT."
        }
        require(minimumScore.isFinite() && minimumScore in -1.0..1.0) {
            "Minimum similarity must be finite and between -1 and 1."
        }

        val queryArray = query.toFloatArray()
        val scorer = Scorer(snapshot, queryArray)
        require(scorer.queryNorm > 0.0) { "Query vector must not be a zero vector." }

        // A negative threshold asks for an exhaustive ranking; only filter with finite scores.
        if (snapshot.dimensions > COARSE_DIMENSIONS && snapshot.records.size > COARSE_SHORTLIST_MIN &&
            minimumScore >= 0.0
        ) {
            val shortlist = coarseShortlist(snapshot, queryArray, limit)
            if (shortlist != null) return refineShortlist(snapshot, scorer, shortlist, limit, minimumScore)
        }

        // Bounded selection: keeping only the best `limit` hits avoids ordering every record.
        val best = PriorityQueue(limit, WORST_FIRST)
        for (index in snapshot.records.indices) {
            val score = scorer.score(index)
            if (score.isNaN() || score < minimumScore) continue
            offer(best, snapshot.records[index], score, limit)
        }
        return best.sortedWith(BEST_FIRST).take(limit)
    }

    /** Stage 2: exact full-dimension scoring over the coarse shortlist. */
    private fun refineShortlist(
        snapshot: VectorSnapshot,
        scorer: Scorer,
        shortlist: IntArray,
        limit: Int,
        minimumScore: Double,
    ): List<VectorSearchHit> {
        val refined = PriorityQueue(limit, WORST_FIRST)
        for (index in shortlist) {
            val score = scorer.score(index)
            if (score.isNaN() || score < minimumScore) continue
            offer(refined, snapshot.records[index], score, limit)
        }
        return refined.sortedWith(BEST_FIRST).take(limit)
    }

    /** Returns coarse candidate indexes, or null when the query has no 128d direction. */
    private fun coarseShortlist(
        snapshot: VectorSnapshot,
        query: FloatArray,
        limit: Int,
    ): IntArray? {
        val queryPrefixNorm = prefixNorm(query, COARSE_DIMENSIONS)
        if (queryPrefixNorm == 0.0) return null
        val capacity =
            minOf(
                snapshot.records.size,
                maxOf(COARSE_SHORTLIST_MIN, limit * COARSE_SHORTLIST_MULTIPLIER),
            )
        val best = PriorityQueue<CoarseCandidate>(capacity, WORST_FIRST_COARSE)
        snapshot.records.forEachIndexed { index, record ->
            val score = coarseScore(record.values, query, queryPrefixNorm)
            if (!score.isNaN()) {
                offerCoarse(best, CoarseCandidate(index, score, record.id), capacity)
            }
        }
        return best.sortedWith(BEST_FIRST_COARSE).map(CoarseCandidate::index).toIntArray()
    }

    private fun coarseScore(
        values: List<Float>,
        query: FloatArray,
        queryNorm: Double,
    ): Double =
        if (values is QuantizedVectorView && values.isQuantized) {
            val recordNorm = values.prefixByteNorm(COARSE_DIMENSIONS)
            if (recordNorm == 0.0) {
                Double.NaN
            } else {
                (values.dotPrefix(query, COARSE_DIMENSIONS) / (queryNorm * recordNorm)).coerceIn(-1.0, 1.0)
            }
        } else {
            var dot = 0.0
            var squared = 0.0
            for (index in 0 until COARSE_DIMENSIONS) {
                val value = values[index].toDouble()
                dot += value * query[index]
                squared += value * value
            }
            val recordNorm = sqrt(squared)
            if (recordNorm == 0.0) Double.NaN else (dot / (queryNorm * recordNorm)).coerceIn(-1.0, 1.0)
        }

    private fun offerCoarse(
        best: PriorityQueue<CoarseCandidate>,
        candidate: CoarseCandidate,
        capacity: Int,
    ) {
        if (best.size < capacity) {
            best.add(candidate)
        } else if (BEST_FIRST_COARSE.compare(candidate, best.peek()) < 0) {
            best.poll()
            best.add(candidate)
        }
    }

    private fun prefixNorm(
        query: FloatArray,
        dimensions: Int,
    ): Double {
        var squared = 0.0
        for (index in 0 until dimensions) {
            val value = query[index].toDouble()
            squared += value * value
        }
        return sqrt(squared)
    }

    private fun offer(
        best: PriorityQueue<VectorSearchHit>,
        record: StoredVector,
        score: Double,
        limit: Int,
    ) {
        val hit =
            VectorSearchHit(
                id = record.id,
                score = score,
                sourceTextSha256 = record.sourceTextSha256,
                createdAtEpochMillis = record.createdAtEpochMillis,
            )
        if (best.size < limit) {
            best.add(hit)
        } else if (BEST_FIRST.compare(hit, best.peek()) < 0) {
            best.poll()
            best.add(hit)
        }
    }

    /**
     * Scores one record against a fixed query, reusing the scratch buffer and the cached
     * magnitude of a legacy float32 snapshot.
     */
    private class Scorer(
        private val snapshot: VectorSnapshot,
        private val query: FloatArray,
    ) {
        val queryNorm: Double = norm(query)
        private var legacyValues: FloatArray? = null
        private var legacyNorms: DoubleArray? = null

        /** Returns [Double.NaN] for a stored zero vector, which has no defined direction. */
        fun score(index: Int): Double {
            val values = snapshot.records[index].values
            return if (values is QuantizedVectorView && values.isQuantized) {
                quantizedScore(values)
            } else {
                legacyScore(values, index)
            }
        }

        /**
         * Byte-space cosine. Only the dot product touches the query; the stored magnitude is
         * computed once per vector and cached, so repeated searches do not re-derive it.
         */
        private fun quantizedScore(values: QuantizedVectorView): Double {
            val recordNorm = values.byteNorm()
            return if (recordNorm == 0.0) {
                Double.NaN
            } else {
                (values.dot(query) / (queryNorm * recordNorm)).coerceIn(-1.0, 1.0)
            }
        }

        /** Legacy float32 path, unchanged: dequantize once, then a dot product. */
        private fun legacyScore(
            values: List<Float>,
            index: Int,
        ): Double {
            val scratch = legacyValues ?: FloatArray(snapshot.dimensions).also { legacyValues = it }
            if (values is FloatVectorView) {
                values.fillInto(scratch)
            } else {
                for (position in scratch.indices) scratch[position] = values[position]
            }
            val recordNorm = (legacyNorms ?: snapshot.norms.also { legacyNorms = it })[index]
            return if (recordNorm == 0.0) {
                Double.NaN
            } else {
                var dot = 0.0
                for (position in query.indices) {
                    dot += query[position].toDouble() * scratch[position].toDouble()
                }
                (dot / (queryNorm * recordNorm)).coerceIn(-1.0, 1.0)
            }
        }
    }

    private fun norm(vector: FloatArray): Double {
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value.toDouble()
        return sqrt(squared)
    }

    const val COARSE_DIMENSIONS = 128
    const val COARSE_SHORTLIST_MIN = 32
    private const val COARSE_SHORTLIST_MULTIPLIER = 4
    const val DEFAULT_RESULT_LIMIT = 5
    const val MAX_RESULT_LIMIT = 100
    const val DEFAULT_MINIMUM_SCORE = 0.35

    /** Highest score first, then ascending id, matching the documented stable tie-break. */
    private val BEST_FIRST = compareByDescending<VectorSearchHit> { it.score }.thenBy { it.id }

    /** Inverse of [BEST_FIRST]: the queue head is always the hit that would be evicted next. */
    private val WORST_FIRST = compareBy<VectorSearchHit> { it.score }.thenByDescending { it.id }

    private val BEST_FIRST_COARSE =
        compareByDescending<CoarseCandidate> { it.score }.thenBy { it.id }

    private val WORST_FIRST_COARSE =
        compareBy<CoarseCandidate> { it.score }.thenByDescending { it.id }
}

private data class CoarseCandidate(
    val index: Int,
    val score: Double,
    val id: String,
)
