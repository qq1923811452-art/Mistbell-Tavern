package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.VectorSnapshotReadResult
import com.mistbell.tavern.v3.core.memory.VectorSnapshotProvider

/**
 * Bridges the durable vector store into retrieval, preserving incompatibility/corruption states.
 * The expected model ID and dimensions must come from the actually loaded local embedding runtime,
 * not from a hard-coded guess.
 */
class PersistedVectorSnapshotProvider(
    private val store: VectorSnapshotLoader,
    private val expectedModelId: () -> String?,
    private val expectedDimensions: () -> Int?,
) : VectorSnapshotProvider {
    override fun loadSnapshot(): VectorSnapshotReadResult {
        val modelId = expectedModelId()?.takeIf(String::isNotBlank)
            ?: return VectorSnapshotReadResult.Incompatible(
                "No active local embedding model is available.",
            )
        val dimensions = expectedDimensions()?.takeIf { it > 0 }
            ?: return VectorSnapshotReadResult.Incompatible(
                "The active local embedding dimension is unavailable.",
            )
        return when (val loaded = store.load(modelId, dimensions)) {
            VectorSnapshotLoadResult.Missing -> VectorSnapshotReadResult.Missing
            is VectorSnapshotLoadResult.Loaded -> VectorSnapshotReadResult.Loaded(loaded.snapshot)
            is VectorSnapshotLoadResult.Incompatible ->
                VectorSnapshotReadResult.Incompatible(loaded.reason)
            is VectorSnapshotLoadResult.Corrupt -> VectorSnapshotReadResult.Corrupt(loaded.reason)
        }
    }
}
