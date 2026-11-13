package com.mistbell.tavern.v3.feature.worldbook

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** Soft semantic recall for worldbook entries that keyword activation did not hard-trigger. */
object WorldbookSemanticSoftGate {
    private val cache = ConcurrentHashMap<String, List<Float>>()

    @Suppress("LongParameterList")
    suspend fun select(
        candidates: List<WorldBookEntry>,
        queryVector: List<Float>,
        embedDocuments: suspend (List<String>) -> List<List<Float>>,
        remainingBudget: Int,
        minSimilarity: Double = DEFAULT_MIN_SIMILARITY,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): List<WorldBookEntry> {
        val eligible = eligible(candidates)
        val queryNorm = norm(queryVector)
        val invalidQuery = queryVector.isEmpty() || queryNorm == 0.0
        val invalidBudget = remainingBudget <= 0 || maxEntries <= 0
        return if (eligible.isEmpty() || invalidQuery || invalidBudget) {
            emptyList()
        } else {
            val vectors = ensureVectors(eligible, embedDocuments)
            if (vectors == null) {
                emptyList()
            } else {
                val ranked =
                    eligible.mapNotNull { entry ->
                        val vector = vectors[cacheKey(entry)] ?: return@mapNotNull null
                        val score = cosine(queryVector, vector, queryNorm)
                        if (score < minSimilarity) null else ScoredEntry(entry, score)
                    }.sortedWith(compareByDescending<ScoredEntry> { it.score }.thenBy { it.entry.id })
                fitBudget(ranked, remainingBudget, maxEntries)
            }
        }
    }

    private fun eligible(candidates: List<WorldBookEntry>): List<WorldBookEntry> =
        candidates.filter { entry -> !entry.disable && !entry.constant && entry.content.isNotBlank() }

    private suspend fun ensureVectors(
        eligible: List<WorldBookEntry>,
        embedDocuments: suspend (List<String>) -> List<List<Float>>,
    ): Map<String, List<Float>>? {
        val missing = eligible.filter { entry -> !cache.containsKey(cacheKey(entry)) }
        if (missing.isNotEmpty()) {
            val vectors = embedDocuments(missing.map { it.content })
            if (vectors.size != missing.size) return null
            missing.forEachIndexed { index, entry -> cache[cacheKey(entry)] = vectors[index].toList() }
        }
        return eligible.associate { entry -> cacheKey(entry) to cache.getValue(cacheKey(entry)) }
    }

    private fun fitBudget(
        ranked: List<ScoredEntry>,
        remainingBudget: Int,
        maxEntries: Int,
    ): List<WorldBookEntry> {
        var used = 0
        return ranked.fold(mutableListOf()) { selected, candidate ->
            val length = candidate.entry.content.length
            if (selected.size < maxEntries && used + length <= remainingBudget) {
                selected += candidate.entry
                used += length
            }
            selected
        }
    }

    private data class ScoredEntry(val entry: WorldBookEntry, val score: Double)

    private fun cacheKey(entry: WorldBookEntry): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest((entry.content + "\u0000" + entry.comment).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "${entry.id}|$digest"
    }

    private fun norm(vector: List<Float>): Double {
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value.toDouble()
        return sqrt(squared)
    }

    private fun cosine(
        left: List<Float>,
        right: List<Float>,
        leftNorm: Double,
    ): Double {
        if (left.size != right.size) return 0.0
        var dot = 0.0
        var rightSquared = 0.0
        for (index in left.indices) {
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            rightSquared += b * b
        }
        val rightNorm = sqrt(rightSquared)
        return if (rightNorm == 0.0) 0.0 else (dot / (leftNorm * rightNorm)).coerceIn(-1.0, 1.0)
    }

    private const val DEFAULT_MIN_SIMILARITY = 0.68
    private const val DEFAULT_MAX_ENTRIES = 3
}
