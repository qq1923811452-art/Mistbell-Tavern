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

        val queryValues = query.toFloatArray()
        val queryNorm = norm(queryValues)
        require(queryNorm > 0.0) { "Query vector must not be a zero vector." }

        // Bounded selection: keeping only the best `limit` hits avoids ordering every record.
        val best = PriorityQueue(limit, WORST_FIRST)
        var legacyValues: FloatArray? = null
        var legacyNorms: DoubleArray? = null

        for (index in snapshot.records.indices) {
            val record = snapshot.records[index]
            val values = record.values
            val score = if (values is QuantizedVectorView && values.isQuantized) {
                values.score(queryValues, queryNorm)
            } else {
                // Legacy float32 path, unchanged: dequantize once, then a dot product.
                val scratch = legacyValues ?: FloatArray(snapshot.dimensions).also { legacyValues = it }
                if (values is FloatVectorView) {
                    values.fillInto(scratch)
                } else {
                    for (position in scratch.indices) {
                        scratch[position] = values[position]
                    }
                }
                val norms = legacyNorms ?: snapshot.norms.also { legacyNorms = it }
                val recordNorm = norms[index]
                if (recordNorm == 0.0) {
                    Double.NaN
                } else {
                    var dot = 0.0
                    for (position in queryValues.indices) {
                        dot += queryValues[position].toDouble() * scratch[position].toDouble()
                    }
                    (dot / (queryNorm * recordNorm)).coerceIn(-1.0, 1.0)
                }
            }
            if (score.isNaN() || score < minimumScore) continue

            val hit = VectorSearchHit(
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

        return best.sortedWith(BEST_FIRST).take(limit)
    }

    private fun norm(vector: FloatArray): Double {
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value.toDouble()
        return sqrt(squared)
    }

    const val DEFAULT_RESULT_LIMIT = 5
    const val MAX_RESULT_LIMIT = 100
    const val DEFAULT_MINIMUM_SCORE = 0.35

    /** Highest score first, then ascending id, matching the documented stable tie-break. */
    private val BEST_FIRST = compareByDescending<VectorSearchHit> { it.score }.thenBy { it.id }

    /** Inverse of [BEST_FIRST]: the queue head is always the hit that would be evicted next. */
    private val WORST_FIRST = compareBy<VectorSearchHit> { it.score }.thenByDescending { it.id }
}
