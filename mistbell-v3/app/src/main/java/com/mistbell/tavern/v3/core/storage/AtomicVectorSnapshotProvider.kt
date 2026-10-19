package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.VectorSnapshotProvider
import com.mistbell.tavern.v3.core.memory.VectorSnapshotReadResult

/**
 * Bridges atomic app-private persistence into the memory retrieval layer.
 * Model ID and dimension checks happen before any stored vectors are used.
 */
class AtomicVectorSnapshotProvider(
    private val store: AtomicVectorSnapshotStore,
    private val expectedModelId: String,
    private val expectedDimensions: Int,
) : VectorSnapshotProvider {
    init {
        require(expectedModelId.isNotBlank()) { "Embedding model ID must not be blank." }
        require(expectedDimensions > 0) { "Embedding dimension must be positive." }
    }

    override fun loadSnapshot(): VectorSnapshotReadResult =
        when (val result = store.load(expectedModelId, expectedDimensions)) {
            VectorSnapshotLoadResult.Missing -> VectorSnapshotReadResult.Missing
            is VectorSnapshotLoadResult.Loaded -> VectorSnapshotReadResult.Loaded(result.snapshot)
            is VectorSnapshotLoadResult.Incompatible ->
                VectorSnapshotReadResult.Incompatible(result.reason)
            is VectorSnapshotLoadResult.Corrupt -> VectorSnapshotReadResult.Corrupt(result.reason)
        }
}
