package com.mistbell.tavern.v3.core.memory

import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMemoryContextServiceTest {
    private val text = MemoryTextRecord("fact-1", "The user prefers concise answers.")
    private val snapshot = VectorSnapshot(
        modelId = "local-test-model",
        dimensions = 2,
        records = listOf(
            StoredVector("vector-1", text.sourceTextSha256, 1L, listOf(1f, 0f)),
        ),
    )

    @Test
    fun embedsQueryAndReturnsValidatedRelevantMemory() = runBlocking {
        val service = service(
            snapshotResult = VectorSnapshotReadResult.Loaded(snapshot),
            embedding = QueryEmbeddingProvider { listOf(1f, 0f) },
            texts = MemoryTextProvider { listOf(text) },
        )

        val result = service.retrieveForQuery("What response style does the user prefer?")

        assertTrue(result is VectorMemoryContextResult.Ready)
        assertEquals(text.text, (result as VectorMemoryContextResult.Ready).memories.single().text)
    }

    @Test
    fun missingSnapshotDoesNotCallEmbeddingRuntime() = runBlocking {
        var called = false
        val service = service(
            snapshotResult = VectorSnapshotReadResult.Missing,
            embedding = QueryEmbeddingProvider {
                called = true
                listOf(1f, 0f)
            },
            texts = MemoryTextProvider { listOf(text) },
        )

        val result = service.retrieveForQuery("query")

        assertEquals(
            VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.SNAPSHOT_MISSING,
            ),
            result,
        )
        assertTrue(!called)
    }

    @Test
    fun invalidEmbeddingOutputFailsClosed() = runBlocking {
        val service = service(
            snapshotResult = VectorSnapshotReadResult.Loaded(snapshot),
            embedding = QueryEmbeddingProvider { listOf(Float.NaN, 0f) },
            texts = MemoryTextProvider { listOf(text) },
        )

        assertEquals(
            VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.INVALID_QUERY_VECTOR,
            ),
            service.retrieveForQuery("query"),
        )
    }

    @Test
    fun runtimeFailureIsExplicitAndDoesNotUseRemoteFallback() = runBlocking {
        val service = service(
            snapshotResult = VectorSnapshotReadResult.Loaded(snapshot),
            embedding = QueryEmbeddingProvider { error("local runtime unavailable") },
            texts = MemoryTextProvider { listOf(text) },
        )

        assertEquals(
            VectorMemoryContextResult.Unavailable(
                VectorMemoryContextResult.Reason.EMBEDDING_FAILED,
            ),
            service.retrieveForQuery("query"),
        )
    }

    private fun service(
        snapshotResult: VectorSnapshotReadResult,
        embedding: QueryEmbeddingProvider,
        texts: MemoryTextProvider,
    ) = VectorMemoryContextService(
        snapshots = VectorSnapshotProvider { snapshotResult },
        embeddings = embedding,
        textProvider = texts,
        minimumScore = 0.5,
    )
}
