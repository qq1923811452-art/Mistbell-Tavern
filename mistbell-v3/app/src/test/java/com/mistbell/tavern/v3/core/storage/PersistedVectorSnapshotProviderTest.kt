package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import com.mistbell.tavern.v3.core.memory.VectorSnapshotReadResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistedVectorSnapshotProviderTest {
    @Test
    fun missingRuntimeModelIsReportedAsIncompatibleWithoutLoadingStore() {
        val provider = PersistedVectorSnapshotProvider(
            store = VectorSnapshotLoader { _, _ ->
                error("Store must not be queried without an active model.")
            },
            expectedModelId = { null },
            expectedDimensions = { 3 },
        )

        assertEquals(
            VectorSnapshotReadResult.Incompatible("No active local embedding model is available."),
            provider.loadSnapshot(),
        )
    }

    @Test
    fun invalidDimensionsAreReportedWithoutLoadingStore() {
        val provider = PersistedVectorSnapshotProvider(
            store = VectorSnapshotLoader { _, _ ->
                error("Store must not be queried without valid dimensions.")
            },
            expectedModelId = { "local:embeddinggemma-2:int8" },
            expectedDimensions = { 0 },
        )

        assertEquals(
            VectorSnapshotReadResult.Incompatible(
                "The active local embedding dimension is unavailable.",
            ),
            provider.loadSnapshot(),
        )
    }

    @Test
    fun loadedSnapshotIsPassedThroughFromDurableStore() {
        val snapshot = VectorSnapshot(
            modelId = "local-test",
            dimensions = 2,
            records = emptyList(),
        )
        val provider = PersistedVectorSnapshotProvider(
            store = VectorSnapshotLoader { modelId, dimensions ->
                assertEquals("local-test", modelId)
                assertEquals(2, dimensions)
                VectorSnapshotLoadResult.Loaded(snapshot)
            },
            expectedModelId = { "local-test" },
            expectedDimensions = { 2 },
        )

        assertEquals(VectorSnapshotReadResult.Loaded(snapshot), provider.loadSnapshot())
    }

    @Test
    fun corruptionStateIsNotHiddenAsMissingData() {
        val provider = PersistedVectorSnapshotProvider(
            store = VectorSnapshotLoader { _, _ ->
                VectorSnapshotLoadResult.Corrupt("checksum mismatch")
            },
            expectedModelId = { "local-test" },
            expectedDimensions = { 2 },
        )

        assertEquals(
            VectorSnapshotReadResult.Corrupt("checksum mismatch"),
            provider.loadSnapshot(),
        )
    }
}
