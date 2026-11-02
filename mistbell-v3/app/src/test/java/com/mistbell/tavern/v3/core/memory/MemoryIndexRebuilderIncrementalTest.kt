package com.mistbell.tavern.v3.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIndexRebuilderIncrementalTest {
    @Test
    fun unchangedRecordsReuseTheirVectorsInsteadOfCallingTheRuntime() = runBlocking {
        val runtime = BatchRuntime()
        val initial = (1..10).map { MemoryTextRecord("rec-%02d".format(it), "fact $it") }
        val first = rebuild(runtime, initial, previous = null)
        assertEquals(10, first.progress.embeddedRecords)
        assertEquals(0, first.progress.reusedRecords)

        runtime.embedded.clear()
        val edited = initial.map { record ->
            if (record.id == "rec-03") MemoryTextRecord(record.id, "fact 3 corrected") else record
        }
        val second = rebuild(runtime, edited, previous = first.snapshot)

        assertEquals(8, second.progress.reusedRecords)
        assertEquals(1, second.progress.embeddedRecords)
        assertEquals(1, runtime.embedded.size)
        assertEquals("fact 3 corrected", runtime.embedded.single())

        val before = first.snapshot.records.associateBy(StoredVector::id)
        second.snapshot.records.forEach { updated ->
            val original = before.getValue(updated.id)
            if (updated.id != "rec-03") {
                assertEquals(original.values, updated.values)
                // Reused entries keep their original index timestamp so churn stays visible.
                assertEquals(original.createdAtEpochMillis, updated.createdAtEpochMillis)
            }
        }
    }

    @Test
    fun aDifferentModelOrDimensionInvalidatesEveryVector() = runBlocking {
        val initial = (1..5).map { MemoryTextRecord("r$it", "text $it") }
        val first = rebuild(BatchRuntime(modelId = "model-a", dimensions = 8), initial, previous = null)

        listOf(
            BatchRuntime(modelId = "model-b", dimensions = 8),
            BatchRuntime(modelId = "model-a", dimensions = 16),
        ).forEach { runtime ->
            val result = rebuild(runtime, initial, previous = first.snapshot)
            assertEquals(0, result.progress.reusedRecords)
            assertEquals(5, result.progress.embeddedRecords)
            assertEquals(result.progress.embeddedRecords, runtime.embedded.size)
        }
    }

    @Test
    fun passagesAreEmbeddedInConfiguredBatches() = runBlocking {
        val runtime = BatchRuntime(batchSize = 4)
        val records = (1..10).map { MemoryTextRecord("r%02d".format(it), "text $it") }
        val result = rebuild(runtime, records, previous = null)

        assertEquals(3, result.progress.inferenceBatches)
        assertEquals(10, runtime.embedded.size)
        assertTrue("runtime never received more than its batch limit", runtime.maxObservedBatch <= 4)
    }

    @Test
    fun deletingARecordKeepsTheRestReusableAndDropsTheVector() = runBlocking {
        val initial = (1..6).map { MemoryTextRecord("r$it", "text $it") }
        val first = rebuild(BatchRuntime(), initial, previous = null)
        val trimmed = initial.filterNot { it.id == "r2" }
        val second = rebuild(BatchRuntime(), trimmed, previous = first.snapshot)

        assertEquals(5, second.snapshot.records.size)
        assertTrue(second.snapshot.records.none { it.id == "r2" })
        assertEquals(5, second.progress.reusedRecords)
        assertEquals(0, second.progress.embeddedRecords)
    }

    @Test
    fun rebuiltSnapshotStillScoresUnchangedRecordsIdentically() = runBlocking {
        val initial = (1..8).map { MemoryTextRecord("r$it", "text $it") }
        val first = rebuild(BatchRuntime(), initial, previous = null)
        val edited = initial.map { if (it.id == "r4") MemoryTextRecord(it.id, "edited") else it }
        val second = rebuild(BatchRuntime(), edited, previous = first.snapshot)

        val query = first.snapshot.records.first().values.toList()
        val before = VectorSimilaritySearch.search(first.snapshot, query, limit = 8, minimumScore = -1.0)
        val after = VectorSimilaritySearch.search(second.snapshot, query, limit = 8, minimumScore = -1.0)
        before.filter { it.id != "r4" }.forEach { hit ->
            val updated = after.first { it.id == hit.id }
            assertEquals(hit.score, updated.score, 0.0)
        }
    }

    private class RebuildOutcome(
        val snapshot: VectorSnapshot,
        val progress: MemoryIndexRebuildProgress,
    )

    private suspend fun rebuild(
        runtime: BatchRuntime,
        records: List<MemoryTextRecord>,
        previous: VectorSnapshot?,
    ): RebuildOutcome {
        var snapshot: VectorSnapshot? = null
        var progress: MemoryIndexRebuildProgress? = null
        val result = MemoryIndexRebuilder(
            runtime = runtime,
            saveSourceTexts = { },
            saveSnapshot = { snapshot = it },
            clockMillis = { 1_000L },
            onProgress = { progress = it },
        ).rebuild(records, previous)
        assertTrue("rebuild failed: $result", result is MemoryIndexRebuildResult.Success)
        assertNotNull(snapshot)
        assertNotNull(progress)
        return RebuildOutcome(requireNotNull(snapshot), requireNotNull(progress))
    }

    private class BatchRuntime(
        override val modelId: String = "model-a",
        override val dimensions: Int = 8,
        private val batchSize: Int = 4,
    ) : PassageEmbeddingRuntime {
        val embedded = mutableListOf<String>()
        var maxObservedBatch = 0

        override suspend fun embed(text: String): List<Float> = vectorFor(text)

        override val passageBatchSize: Int get() = batchSize

        override suspend fun embedPassages(texts: List<String>): List<List<Float>> {
            require(texts.size <= batchSize) { "batch exceeded the configured size" }
            maxObservedBatch = maxOf(maxObservedBatch, texts.size)
            embedded += texts
            return texts.map(::vectorFor)
        }

        private fun vectorFor(text: String): List<Float> {
            val seed = text.fold(7L) { acc, character -> acc * 31 + character.code }
            val random = java.util.Random(seed)
            return List(dimensions) { random.nextGaussian().toFloat() }
        }
    }
}
