package com.mistbell.tavern.v3.feature.embedding

/**
 * Task formatting for EmbeddingGemma 2's asymmetric retrieval objective.
 *
 * Keep query and corpus formatting distinct: the model was trained with different instructions
 * for search queries and documents. This policy does not load a model or claim runtime availability.
 */
object EmbeddingGemma2TextPolicy {
    const val MODEL_ID = "google/embeddinggemma-2"
    const val POLICY_VERSION = 2
    const val NATIVE_DIMENSIONS = 768
    const val MAX_EMBED_CHARS = 2_000
    const val MAX_SAFE_FORMATTED_INPUT_BYTES = 2_048
    private const val SEARCH_QUERY_PREFIX = "task: search result | query: "

    // Android 8 (API 26) has no Character.toString(int); size UTF-8 bytes from code points directly.
    private const val UTF8_ONE_BYTE_MAX = 0x7F
    private const val UTF8_TWO_BYTE_MAX = 0x7FF
    private const val UTF8_THREE_BYTE_MAX = 0xFFFF
    private const val SURROGATE_MIN = 0xD800
    private const val SURROGATE_MAX = 0xDFFF
    private const val UTF8_ONE_BYTE = 1
    private const val UTF8_TWO_BYTES = 2
    private const val UTF8_THREE_BYTES = 3
    private const val UTF8_FOUR_BYTES = 4

    /** Truncate by UTF-16 code units without splitting a surrogate pair at the boundary. */
    fun truncateInput(text: String): String {
        val end = minOf(text.length, MAX_EMBED_CHARS)
        return text.substring(0, safeUtf16End(text, end))
    }

    private fun safeUtf16End(
        text: String,
        end: Int,
    ): Int {
        if (end == 0 || end == text.length) return end
        return if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end - 1 else end
    }

    /** Applies a conservative UTF-8 byte budget to prefix, title, and content together. */
    fun truncateFormattedInput(text: String): String {
        val maxBytes = MAX_SAFE_FORMATTED_INPUT_BYTES
        return truncateUtf8(text, maxBytes, maxBytes)
    }

    private fun truncateUtf8(
        text: String,
        maxBytes: Int,
        maxChars: Int = MAX_EMBED_CHARS,
    ): String {
        var charIndex = 0
        var byteCount = 0
        val charLimit = minOf(text.length, maxChars)
        while (charIndex < charLimit) {
            val codePoint = Character.codePointAt(text, charIndex)
            val chars = Character.charCount(codePoint)
            val bytes = utf8ByteCount(codePoint)
            if (charIndex + chars > charLimit || byteCount + bytes > maxBytes) {
                return text.substring(0, charIndex)
            }
            byteCount += bytes
            charIndex += chars
        }
        return text.substring(0, charIndex)
    }

    /** UTF-8 length of one code point; a lone surrogate is encoded as a single replacement byte. */
    private fun utf8ByteCount(codePoint: Int): Int =
        when {
            codePoint in SURROGATE_MIN..SURROGATE_MAX -> UTF8_ONE_BYTE
            codePoint <= UTF8_ONE_BYTE_MAX -> UTF8_ONE_BYTE
            codePoint <= UTF8_TWO_BYTE_MAX -> UTF8_TWO_BYTES
            codePoint <= UTF8_THREE_BYTE_MAX -> UTF8_THREE_BYTES
            else -> UTF8_FOUR_BYTES
        }

    /**
     * Output tiers supported by this model's native 768-dimensional Matryoshka vector.
     * Larger tiers must not be offered as selectable output sizes for this model: padding or
     * inventing dimensions would not create a genuine higher-dimensional embedding.
     */
    val supportedDimensions: Set<Int> = setOf(128, 256, 512, 768)

    fun formatSearchQuery(query: String): String {
        require(query.isNotBlank()) { "Search query must not be blank." }
        val prefix = SEARCH_QUERY_PREFIX
        val boundedQuery =
            truncateUtf8(
                query.trim(),
                MAX_SAFE_FORMATTED_INPUT_BYTES - prefix.toByteArray(Charsets.UTF_8).size,
                maxChars = MAX_EMBED_CHARS,
            )
        require(boundedQuery.isNotBlank()) { "Search query is too long to fit the safe model input budget." }
        return prefix + boundedQuery
    }

    fun formatDocument(
        text: String,
        title: String? = null,
    ): String {
        require(text.isNotBlank()) { "Document text must not be blank." }
        val rawTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: "none"
        require('\n' !in rawTitle && '\r' !in rawTitle) {
            "Document title must be a single line."
        }
        val prefix = "title: "
        val separator = " | text: "
        val fixedBytes = (prefix + separator).toByteArray(Charsets.UTF_8).size
        // Reserve at least half of the available bytes for the document's retrieval content.
        val titleBudget = (MAX_SAFE_FORMATTED_INPUT_BYTES - fixedBytes) / 2
        val safeTitle = truncateUtf8(rawTitle, titleBudget, MAX_EMBED_CHARS).ifBlank { "none" }
        val taskPrefix = "$prefix$safeTitle$separator"
        val contentBudget = MAX_SAFE_FORMATTED_INPUT_BYTES - taskPrefix.toByteArray(Charsets.UTF_8).size
        val safeText = truncateUtf8(text.trim(), contentBudget, MAX_EMBED_CHARS)
        require(safeText.isNotBlank()) { "Document text is too long to fit the safe model input budget." }
        return taskPrefix + safeText
    }

    /**
     * Truncates a native vector to a supported Matryoshka dimension and L2-normalizes it.
     * Truncation without normalization changes cosine-similarity ranking.
     */
    fun truncateAndNormalize(
        vector: List<Float>,
        dimensions: Int,
    ): List<Float> {
        require(dimensions in supportedDimensions) {
            "Unsupported embedding output dimension for EmbeddingGemma 2: $dimensions. " +
                "Choose 128, 256, 512, or 768."
        }
        require(vector.size == NATIVE_DIMENSIONS) {
            "Expected a native $NATIVE_DIMENSIONS-dimensional EmbeddingGemma 2 vector."
        }
        require(vector.all(Float::isFinite)) { "Embedding vector contains non-finite values." }
        var normSquared = 0.0
        for (index in 0 until dimensions) {
            val value = vector[index].toDouble()
            normSquared += value * value
        }
        require(normSquared.isFinite() && normSquared > 0.0) {
            "Truncated embedding vector must have a finite non-zero norm."
        }
        val norm = kotlin.math.sqrt(normSquared)
        // Keep primitive FloatArray backing while preserving the existing List<Float> API.
        return FloatArray(dimensions) { index -> (vector[index] / norm).toFloat() }.asList()
    }
}
