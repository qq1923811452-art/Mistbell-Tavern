package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuildResult
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.PassageEmbeddingRuntime
import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedMemoryIndexRebuilderTest {
    @Test
    fun rebuildCurrentIndexReadsDurableTextsAndSavesMatchingSnapshot() {
        val source = listOf(MemoryTextRecord("m1", "A persisted fact"))
        val textStore = FakeTextStore(MemoryTextLoadResult.Loaded(source))
        var savedSnapshot: VectorSnapshot? = null
        val rebuilder = PersistedMemoryIndexRebuilder(
            textStore = textStore,
            vectorStore = VectorSnapshotSaver { savedSnapshot = it },
            runtime = FakeRuntime(),
            clockMillis = { 7L },
        )

        val result = rebuilder.rebuildCurrentIndex()

        assertEquals(MemoryIndexRebuildResult.Success(1, "loaded-model", 2), result)
        assertEquals(source, textStore.saved)
        assertEquals(source.single().sourceTextSha256, savedSnapshot!!.records.single().sourceTextSha256)
        assertEquals("loaded-model", savedSnapshot!!.modelId)
    }

    @Test
    fun refusesToOverwriteCorruptSourceStore() {
        val textStore = FakeTextStore(MemoryTextLoadResult.Corrupt("corrupt"))
        var savedSnapshot: VectorSnapshot? = null
        val result = PersistedMemoryIndexRebuilder(
            textStore = textStore,
            vectorStore = VectorSnapshotSaver { savedSnapshot = it },
            runtime = FakeRuntime(),
            clockMillis = { 7L },
        ).rebuildCurrentIndex()

        assertEquals(MemoryIndexRebuildResult.Stage.INVALID_INPUT,
            (result as MemoryIndexRebuildResult.Failed).stage)
        assertEquals(null, textStore.saved)
        assertNull(savedSnapshot)
    }

    private class FakeTextStore(
        private val loadResult: MemoryTextLoadResult,
    ) : MemoryTextPersistence {
        var saved: List<MemoryTextRecord>? = null

        override fun save(records: List<MemoryTextRecord>) {
            saved = records
        }

        override fun load(): MemoryTextLoadResult = loadResult

        override fun update(
            transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>,
        ): List<MemoryTextRecord> {
            val current = (loadResult as? MemoryTextLoadResult.Loaded)?.records.orEmpty()
            return transform(current).also(::save)
        }

        override fun loadMemoryTexts(): List<MemoryTextRecord> =
            (loadResult as? MemoryTextLoadResult.Loaded)?.records.orEmpty()
    }

    private class FakeRuntime : PassageEmbeddingRuntime {
        override val modelId: String = "loaded-model"
        override val dimensions: Int = 2
        override fun embed(text: String): List<Float> = listOf(0.8f, 0.2f)
    }
}
