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
    verifiedModelSha256: String,
) : PassageEmbeddingRuntime {
    // Snapshot identity includes the verified asset and all input/output policy dependencies.
    // Unknown legacy identities fail closed and trigger a background rebuild once.
    override val modelId: String = snapshotIdentity(adapter.model.modelId, verifiedModelSha256)

    companion object {
        internal fun snapshotIdentity(
            modelId: String,
            modelSha256: String,
            policyVersion: Int = EmbeddingGemma2TextPolicy.POLICY_VERSION,
        ): String {
            require(modelSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Verified model SHA-256 is required." }
            return "$modelId;sha256=${modelSha256.lowercase(java.util.Locale.ROOT)};policy=$policyVersion;" +
                "chars=${EmbeddingGemma2TextPolicy.MAX_EMBED_CHARS};" +
                "bytes=${EmbeddingGemma2RuntimeAdapter.MAX_FORMATTED_INPUT_BYTES};normalization=1"
        }
    }

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
