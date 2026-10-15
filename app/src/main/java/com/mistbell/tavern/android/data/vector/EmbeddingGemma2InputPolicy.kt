package com.mistbell.tavern.android.data.vector

/**
 * EmbeddingGemma 2 uses asymmetric task instructions for memory retrieval.
 *
 * Keep these prefixes aligned with the model card's SearchQuery/Document prompts:
 * query vectors use the search-query instruction; indexed memories use a document
 * instruction with an explicit missing-title marker.
 */
internal object EmbeddingGemma2InputPolicy {
    private const val QUERY_PREFIX = "task: search result | query: "
    private const val DOCUMENT_PREFIX = "title: none | text: "

    fun format(
        text: String,
        isQuery: Boolean,
    ): String = (if (isQuery) QUERY_PREFIX else DOCUMENT_PREFIX) + text.trim()
}
