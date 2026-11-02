package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.memory.PassageEmbeddingRuntime

/**
 * Adapts EmbeddingGemma 2's distinct query and document tasks to the vector-memory contracts.
 *
 * Query retrieval uses the model's search-query instruction; index building uses its
 * search-result/document instruction. The wrapper reports the selected adapter's model and
 * dimensions rather than duplicating configuration that could drift out of sync.
 */
class EmbeddingGemma2MemoryRuntime(
    private val adapter: EmbeddingGemma2RuntimeAdapter,
) : PassageEmbeddingRuntime {
    override val modelId: String
        get() = adapter.model.modelId

    override val dimensions: Int
        get() = adapter.model.dimensions

    override suspend fun embed(text: String): List<Float> =
        adapter.embedQueries(listOf(text)).vectors.single()

    override suspend fun embedPassage(text: String): List<Float> =
        adapter.embedDocuments(listOf(text)).vectors.single()

    /** The adapter caps one inference call at the model's configured batch size. */
    override val passageBatchSize: Int
        get() = adapter.model.maxBatchSize

    override suspend fun embedPassages(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()
        // One call per configured batch; embedDocuments rejects an oversized batch instead of
        // silently splitting it, so the caller's chunking stays authoritative.
        val result = ArrayList<List<Float>>(texts.size)
        for (chunk in texts.chunked(adapter.model.maxBatchSize.coerceAtLeast(1))) {
            result += adapter.embedDocuments(chunk).vectors
        }
        return result
    }
}
