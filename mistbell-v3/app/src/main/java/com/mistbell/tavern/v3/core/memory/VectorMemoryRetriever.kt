package com.mistbell.tavern.v3.core.memory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

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

enum class MemoryRetrievalSource {
    VECTOR,
    KEYWORD,
    HYBRID,
}

/** A memory passage selected for prompt injection, ordered by descending fused relevance. */
data class RetrievedMemory(
    val id: String,
    val text: String,
    val similarity: Double,
    val source: MemoryRetrievalSource = MemoryRetrievalSource.VECTOR,
)

/**
 * Joins vector search hits to their source text using SHA-256, never by vector ID alone.
 * This prevents stale vectors from injecting text that has changed since embedding.
 *
 * Vector and keyword rankings are fused with reciprocal rank fusion (RRF). The character
 * budget is a deterministic safety bound, not a tokenizer-based token budget.
 */
object VectorMemoryRetriever {
    private val wordRegex = Regex("[\\p{L}\\p{N}]+")

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
        require(limit >= 1) { "Result limit must be at least 1." }
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
            var hasVectorMatch: Boolean = false,
            var hasKeywordMatch: Boolean = false,
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
            candidate.hasVectorMatch = true
            candidate.rrfScore += 1.0 / (rrfConstant + vectorRank)
        }

        val queryTerms = tokenizeQuery(queryText.orEmpty())
        if (queryTerms.isNotEmpty()) {
            val keywordRanked = texts.asSequence()
                .map { record ->
                    val matchedTerms = countMatchedQueryTerms(record.text, queryTerms)
                    record to matchedTerms
                }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<MemoryTextRecord, Int>> { it.second }
                        .thenBy { it.first.id },
                )
                .take(VectorSimilaritySearch.MAX_RESULT_LIMIT)
                .toList()

            val seenKeywordHashes = mutableSetOf<String>()
            var keywordRank = 0
            for ((record, _) in keywordRanked) {
                val hash = record.sourceTextSha256
                if (!seenKeywordHashes.add(hash)) continue
                val source = textByHash[hash] ?: continue
                keywordRank++
                val candidate = candidatesByHash.getOrPut(hash) {
                    RankedCandidate(source, similarity = 0.0)
                }
                candidate.hasKeywordMatch = true
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
            val end = minOf(normalizedText.length, remaining).let { proposedEnd ->
                // Do not split a UTF-16 surrogate pair when truncating to the remaining budget.
                if (proposedEnd < normalizedText.length &&
                    proposedEnd > 0 &&
                    Character.isHighSurrogate(normalizedText[proposedEnd - 1])
                ) {
                    proposedEnd - 1
                } else {
                    proposedEnd
                }
            }
            if (end <= 0) continue
            val selectedText = normalizedText.substring(0, end)
            val source = when {
                candidate.hasVectorMatch && candidate.hasKeywordMatch -> MemoryRetrievalSource.HYBRID
                candidate.hasKeywordMatch -> MemoryRetrievalSource.KEYWORD
                else -> MemoryRetrievalSource.VECTOR
            }
            result += RetrievedMemory(
                id = candidate.source.id,
                text = selectedText,
                similarity = candidate.similarity,
                source = source,
            )
            usedCharacters += selectedText.length
        }
        return result
    }

    const val DEFAULT_MAX_CHARACTERS = 6_000
    const val MAX_CHARACTERS = 100_000
    const val DEFAULT_RRF_CONSTANT = 60
    const val MAX_RRF_CONSTANT = 1_000
    const val MAX_KEYWORD_QUERY_TERMS = 64
    const val MAX_KEYWORD_SCAN_CHARACTERS = 20_000

    /**
     * Keeps English/numeric words, and uses CJK bigrams so unspaced Chinese sentences can
     * match shorter query concepts. A one-character CJK query remains searchable.
     */
    private fun tokenizeQuery(text: String): Set<String> {
        val tokens = linkedSetOf<String>()
        val chunks = wordRegex.findAll(text.lowercase(Locale.ROOT))
        for (chunk in chunks) {
            val segment = chunk.value
            var hanRun = StringBuilder()
            var otherRun = StringBuilder()

            fun flushHan() {
                if (hanRun.isEmpty()) return
                val value = hanRun.toString()
                if (value.length == 1) {
                    tokens += value
                } else {
                    tokens += value.windowed(2, 1)
                }
                hanRun = StringBuilder()
            }

            fun flushOther() {
                if (otherRun.length >= 2) tokens += otherRun.toString()
                otherRun = StringBuilder()
            }

            for (character in segment) {
                if (isHan(character)) {
                    flushOther()
                    hanRun.append(character)
                } else {
                    flushHan()
                    otherRun.append(character)
                }
            }
            flushHan()
            flushOther()
        }
        return tokens.take(MAX_KEYWORD_QUERY_TERMS).toSet()
    }

    /** Tokenize each bounded candidate once; only count terms actually present in the query. */
    private fun countMatchedQueryTerms(text: String, queryTerms: Set<String>): Int {
        if (queryTerms.isEmpty() || text.isEmpty()) return 0
        val bounded = text.substring(0, minOf(text.length, MAX_KEYWORD_SCAN_CHARACTERS))
            .lowercase(Locale.ROOT)
        val matched = mutableSetOf<String>()
        for (chunk in wordRegex.findAll(bounded)) {
            val segment = chunk.value
            var index = 0
            while (index < segment.length) {
                val character = segment[index]
                if (isHan(character)) {
                    val unigram = character.toString()
                    if (unigram in queryTerms) matched += unigram
                    if (index + 1 < segment.length && isHan(segment[index + 1])) {
                        val bigram = segment.substring(index, index + 2)
                        if (bigram in queryTerms) matched += bigram
                    }
                    index++
                } else {
                    val start = index
                    while (index < segment.length && !isHan(segment[index])) index++
                    val token = segment.substring(start, index)
                    if (token.length >= 2 && token in queryTerms) matched += token
                }
            }
        }
        return matched.size
    }

    private fun isHan(character: Char): Boolean =
        character in '\u3400'..'\u4DBF' ||
            character in '\u4E00'..'\u9FFF' ||
            character in '\uF900'..'\uFAFF'
}

private fun sha256Text(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
