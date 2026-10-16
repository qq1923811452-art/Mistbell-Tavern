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
        get() = sha256(text)
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
    ): List<RetrievedMemory> {
        require(maxCharacters in 1..MAX_CHARACTERS) {
            "Memory character budget must be between 1 and $MAX_CHARACTERS."
        }
        require(texts.map(MemoryTextRecord::id).distinct().size == texts.size) {
            "Memory text IDs must be unique."
        }

        val textByHash = texts
            .groupBy(MemoryTextRecord::sourceTextSha256)
            .mapValues { (_, records) -> records.sortedBy(MemoryTextRecord::id).first() }
        val seenHashes = mutableSetOf<String>()
        var usedCharacters = 0
        val result = mutableListOf<RetrievedMemory>()

        for (hit in VectorSimilaritySearch.search(snapshot, queryVector, limit, minimumScore)) {
            if (!seenHashes.add(hit.sourceTextSha256)) continue
            val source = textByHash[hit.sourceTextSha256] ?: continue
            val normalizedText = source.text.trim()
            if (normalizedText.isEmpty()) continue
            val remaining = maxCharacters - usedCharacters
            if (remaining <= 0) break
            if (normalizedText.length > remaining) {
                if (result.isEmpty() && remaining > 0) {
                    result += RetrievedMemory(
                        id = source.id,
                        text = normalizedText.take(remaining),
                        similarity = hit.score,
                    )
                }
                break
            }
            result += RetrievedMemory(id = source.id, text = normalizedText, similarity = hit.score)
            usedCharacters += normalizedText.length
        }
        return result
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    const val DEFAULT_MAX_CHARACTERS = 6_000
    const val MAX_CHARACTERS = 100_000
}
