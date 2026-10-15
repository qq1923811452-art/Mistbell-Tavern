package com.mistbell.tavern.android.data.vector

/**
 * EmbeddingGemma 2 is trained with asymmetric instructions for indexed documents and search queries.
 * Keep this policy pure so the prefix behavior can be verified without loading the native runtime.
 */
internal object EmbeddingGemma2InputPolicy {
    private const val QUERY_PREFIX = "task: search query | text: "
    private const val DOCUMENT_PREFIX = "task: search result | text: "

    fun format(text: String, isQuery: Boolean): String =
        (if (isQuery) QUERY_PREFIX else DOCUMENT_PREFIX) + text.trim()
}
