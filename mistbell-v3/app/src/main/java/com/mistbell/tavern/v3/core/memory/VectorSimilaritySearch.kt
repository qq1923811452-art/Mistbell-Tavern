package com.mistbell.tavern.v3.core.memory

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
        val candidateValues = FloatArray(snapshot.dimensions)

        return snapshot.records.asSequence()
            .mapNotNull { record ->
                val recordValues = record.values
                val recordNorm = if (recordValues is FloatVectorView) {
                    recordValues.fillInto(candidateValues)
                    norm(candidateValues)
                } else {
                    var squared = 0.0
                    for (index in candidateValues.indices) {
                        val value = recordValues[index]
                        candidateValues[index] = value
                        squared += value.toDouble() * value.toDouble()
                    }
                    kotlin.math.sqrt(squared)
                }
                if (recordNorm == 0.0) return@mapNotNull null
                var dot = 0.0
                for (index in queryValues.indices) {
                    dot += queryValues[index].toDouble() * candidateValues[index].toDouble()
                }
                val score = (dot / (queryNorm * recordNorm)).coerceIn(-1.0, 1.0)
                if (score < minimumScore) return@mapNotNull null
                VectorSearchHit(
                    id = record.id,
                    score = score,
                    sourceTextSha256 = record.sourceTextSha256,
                    createdAtEpochMillis = record.createdAtEpochMillis,
                )
            }
            .sortedWith(compareByDescending<VectorSearchHit> { it.score }.thenBy { it.id })
            .take(limit)
            .toList()
    }

    private fun norm(vector: FloatArray): Double {
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value.toDouble()
        return sqrt(squared)
    }

    const val DEFAULT_RESULT_LIMIT = 5
    const val MAX_RESULT_LIMIT = 100
    const val DEFAULT_MINIMUM_SCORE = 0.35
}
