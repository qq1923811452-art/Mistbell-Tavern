package com.mistbell.tavern.v3.core.memory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Text stored separately from the vector snapshot; the content hash binds text to its vector. */
data class MemoryTextRecord(
    val id: String,
    val text: String,
) {
    init {
        require(id.isNotBlank()) { "Memory text ID must not be blank." }
        require(text.isNotBlank()) { "Memory text must not be blank." }
    }

    val sourceTextSha256: String
        get() = sha256Text(text)
}

/** A memory passage selected for prompt injection, ordered by descending semantic relevance. */
data class RetrievedMemory(
    val id: String,
    val text: String,
    val similarity: Double,
)

/**
 * Joins vector search hits to their source text using SHA-256, never by vector ID alone.
 * This prevents stale vectors from injecting text that has changed since embedding.
 *
 * The character budget is a deterministic safety bound, not a tokenizer-based token budget.
 * Callers may apply a model-specific token budget after retrieval.
 */
object VectorMemoryRetriever {
    fun retrieve(
        snapshot: VectorSnapshot,
        queryVector: List<Float>,
        texts: List<MemoryTextRecord>,
        limit: Int = VectorSimilaritySearch.DEFAULT_RESULT_LIMIT,
        minimumScore: Double = VectorSimilaritySearch.DEFAULT_MINIMUM_SCORE,
        maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
        queryText: String? = null,
        rrfConstant: Int = DEFAULT_RRF_CONSTANT,
    ): List<RetrievedMemory> {
        require(rrfConstant in 1..MAX_RRF_CONSTANT) { "RRF constant must be between 1 and $MAX_RRF_CONSTANT." }
        require(maxCharacters in 1..MAX_CHARACTERS) {
            "Memory character budget must be between 1 and $MAX_CHARACTERS."
        }
        require(texts.map(MemoryTextRecord::id).distinct().size == texts.size) {
            "Memory text IDs must be unique."
        }

        val textByHash = texts
            .groupBy(MemoryTextRecord::sourceTextSha256)
            .mapValues { (_, records) -> records.sortedBy(MemoryTextRecord::id).first() }
        data class RankedCandidate(
            val source: MemoryTextRecord,
            val similarity: Double,
            var rrfScore: Double = 0.0,
        )

        val candidatesByHash = linkedMapOf<String, RankedCandidate>()
        val vectorHits = VectorSimilaritySearch.search(
            snapshot = snapshot,
            query = queryVector,
            limit = VectorSimilaritySearch.MAX_RESULT_LIMIT,
            minimumScore = minimumScore,
        )
        var vectorRank = 0
        val seenVectorHashes = mutableSetOf<String>()
        for (hit in vectorHits) {
            if (!seenVectorHashes.add(hit.sourceTextSha256)) continue
            val source = textByHash[hit.sourceTextSha256] ?: continue
            vectorRank++
            val candidate = candidatesByHash.getOrPut(hit.sourceTextSha256) {
                RankedCandidate(source, hit.score)
            }
            candidate.rrfScore += 1.0 / (rrfConstant + vectorRank)
        }

        val queryTerms = tokenize(queryText.orEmpty())
        if (queryTerms.isNotEmpty()) {
            val keywordRanked = texts.asSequence()
                .map { record ->
                    val terms = tokenize(record.text)
                    val matchedTerms = queryTerms.count { it in terms }
                    Triple(record, matchedTerms, terms.size)
                }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Triple<MemoryTextRecord, Int, Int>> { it.second }
                        .thenBy { it.first.id },
                )
                .take(VectorSimilaritySearch.MAX_RESULT_LIMIT)
                .toList()

            val seenKeywordHashes = mutableSetOf<String>()
            var keywordRank = 0
            for ((source, _, _) in keywordRanked) {
                val hash = source.sourceTextSha256
                if (!seenKeywordHashes.add(hash)) continue
                keywordRank++
                val candidate = candidatesByHash.getOrPut(hash) {
                    RankedCandidate(source, similarity = 0.0)
                }
                candidate.rrfScore += 1.0 / (rrfConstant + keywordRank)
            }
        }

        var usedCharacters = 0
        val result = mutableListOf<RetrievedMemory>()
        for (candidate in candidatesByHash.values.sortedWith(
            compareByDescending<RankedCandidate> { it.rrfScore }.thenBy { it.source.id },
        )) {
            if (result.size >= limit) break
            val normalizedText = candidate.source.text.trim()
            if (normalizedText.isEmpty()) continue
            val remaining = maxCharacters - usedCharacters
            if (remaining <= 0) break
            if (normalizedText.length > remaining) continue
            result += RetrievedMemory(
                id = candidate.source.id,
                text = normalizedText,
                similarity = candidate.similarity,
            )
            usedCharacters += normalizedText.length
        }
        return result
    }

    const val DEFAULT_MAX_CHARACTERS = 6_000
    const val MAX_CHARACTERS = 100_000
    const val DEFAULT_RRF_CONSTANT = 60
    const val MAX_RRF_CONSTANT = 1_000

    private fun tokenize(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase(java.util.Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()
}

private fun sha256Text(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
