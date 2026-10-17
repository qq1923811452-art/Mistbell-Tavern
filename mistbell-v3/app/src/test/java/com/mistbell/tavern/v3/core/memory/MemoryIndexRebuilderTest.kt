package com.mistbell.tavern.v3.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIndexRebuilderTest {
    @Test
    fun rebuildsSortedSnapshotBoundToSourceHashesAndRuntimeMetadata() = runBlocking {
        val savedTexts = mutableListOf<MemoryTextRecord>()
        var savedSnapshot: VectorSnapshot? = null
        val runtime = TestRuntime()
        val result = MemoryIndexRebuilder(
            runtime = runtime,
            saveSourceTexts = { savedTexts += it },
            saveSnapshot = { savedSnapshot = it },
            clockMillis = { 123L },
        ).rebuild(listOf(MemoryTextRecord("z", "second"), MemoryTextRecord("a", "first")))

        assertEquals(
            MemoryIndexRebuildResult.Success(2, "actual-local-model", 3),
            result,
        )
        assertEquals(listOf("a", "z"), savedTexts.map(MemoryTextRecord::id))
        val snapshot = requireNotNull(savedSnapshot)
        assertEquals("actual-local-model", snapshot.modelId)
        assertEquals(3, snapshot.dimensions)
        assertEquals(listOf("a", "z"), snapshot.records.map(StoredVector::id))
        assertEquals(savedTexts.map(MemoryTextRecord::sourceTextSha256),
            snapshot.records.map(StoredVector::sourceTextSha256))
        assertTrue(snapshot.records.all { it.createdAtEpochMillis == 123L })
    }

    @Test
    fun invalidEmbeddingDoesNotPersistEitherSourceOrSnapshot() = runBlocking {
        var textSaved = false
        var snapshotSaved = false
        val result = MemoryIndexRebuilder(
            runtime = TestRuntime(vector = listOf(1f, Float.NaN, 0f)),
            saveSourceTexts = { textSaved = true },
            saveSnapshot = { snapshotSaved = true },
            clockMillis = { 1L },
        ).rebuild(listOf(MemoryTextRecord("m1", "memory")))

        assertTrue(result is MemoryIndexRebuildResult.Failed)
        assertEquals(MemoryIndexRebuildResult.Stage.EMBEDDING,
            (result as MemoryIndexRebuildResult.Failed).stage)
        assertFalse(textSaved)
        assertFalse(snapshotSaved)
    }

    @Test
    fun sourceTextSaveFailureLeavesSnapshotUntouched() = runBlocking {
        var snapshotSaved = false
        val result = MemoryIndexRebuilder(
            runtime = TestRuntime(),
            saveSourceTexts = { error("disk full") },
            saveSnapshot = { snapshotSaved = true },
            clockMillis = { 1L },
        ).rebuild(listOf(MemoryTextRecord("m1", "memory")))

        assertEquals(MemoryIndexRebuildResult.Stage.SOURCE_TEXT_SAVE,
            (result as MemoryIndexRebuildResult.Failed).stage)
        assertFalse(snapshotSaved)
    }

    @Test
    fun snapshotSaveFailureIsReportedAfterSavingSourceTextsForHashSafeRetry() = runBlocking {
        var sourceSaved = false
        val result = MemoryIndexRebuilder(
            runtime = TestRuntime(),
            saveSourceTexts = { sourceSaved = true },
            saveSnapshot = { error("disk full") },
            clockMillis = { 1L },
        ).rebuild(listOf(MemoryTextRecord("m1", "new memory")))

        assertTrue(sourceSaved)
        assertEquals(MemoryIndexRebuildResult.Stage.SNAPSHOT_SAVE,
            (result as MemoryIndexRebuildResult.Failed).stage)
    }

    @Test
    fun rejectsDuplicateIdsAndNegativeTimestampsBeforeWriting() = runBlocking {
        var writes = 0
        val rebuilder = MemoryIndexRebuilder(
            runtime = TestRuntime(),
            saveSourceTexts = { writes++ },
            saveSnapshot = { writes++ },
            clockMillis = { -1L },
        )
        val duplicateResult = rebuilder.rebuild(
            listOf(MemoryTextRecord("same", "one"), MemoryTextRecord("same", "two")),
        )
        assertEquals(MemoryIndexRebuildResult.Stage.INVALID_INPUT,
            (duplicateResult as MemoryIndexRebuildResult.Failed).stage)
        val timestampResult = rebuilder.rebuild(listOf(MemoryTextRecord("one", "text")))
        assertEquals(MemoryIndexRebuildResult.Stage.INVALID_INPUT,
            (timestampResult as MemoryIndexRebuildResult.Failed).stage)
        assertEquals(0, writes)
    }

    private class TestRuntime(
        private val vector: List<Float> = listOf(1f, 0.5f, 0.25f),
    ) : PassageEmbeddingRuntime {
        override val modelId: String = "actual-local-model"
        override val dimensions: Int = 3

        override suspend fun embed(text: String): List<Float> = vector
    }
}
