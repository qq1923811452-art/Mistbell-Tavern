package com.mistbell.tavern.v3.feature.embedding

/**
 * Task formatting for EmbeddingGemma 2's asymmetric retrieval objective.
 *
 * Keep query and corpus formatting distinct: the model was trained with different instructions
 * for search queries and documents. This policy does not load a model or claim runtime availability.
 */
object EmbeddingGemma2TextPolicy {
    const val MODEL_ID = "google/embeddinggemma-2"
    const val NATIVE_DIMENSIONS = 768
    val supportedDimensions: Set<Int> = setOf(128, 256, 512, 768)

    fun formatSearchQuery(query: String): String {
        require(query.isNotBlank()) { "Search query must not be blank." }
        return "task: search query | text: ${query.trim()}"
    }

    fun formatDocument(text: String, title: String? = null): String {
        require(text.isNotBlank()) { "Document text must not be blank." }
        val safeTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: "none"
        require('\n' !in safeTitle && '\r' !in safeTitle) {
            "Document title must be a single line."
        }
        return "task: search result | title: $safeTitle | text: ${text.trim()}"
    }

    /**
     * Truncates a native vector to a supported Matryoshka dimension and L2-normalizes it.
     * Truncation without normalization changes cosine-similarity ranking.
     */
    fun truncateAndNormalize(vector: List<Float>, dimensions: Int): List<Float> {
        require(dimensions in supportedDimensions) { "Unsupported EmbeddingGemma 2 output dimension." }
        require(vector.size == NATIVE_DIMENSIONS) {
            "Expected a native $NATIVE_DIMENSIONS-dimensional EmbeddingGemma 2 vector."
        }
        require(vector.all(Float::isFinite)) { "Embedding vector contains non-finite values." }
        val truncated = vector.take(dimensions)
        val normSquared = truncated.sumOf { it.toDouble() * it.toDouble() }
        require(normSquared.isFinite() && normSquared > 0.0) {
            "Truncated embedding vector must have a finite non-zero norm."
        }
        val norm = kotlin.math.sqrt(normSquared)
        return truncated.map { (it / norm).toFloat() }
    }
}
