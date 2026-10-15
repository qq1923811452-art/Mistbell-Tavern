package com.mistbell.tavern.android.data.vector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVectorReindexTest {
    @Test
    fun `reindexes legacy entry and removes it only after replacement is stored`() = runBlocking {
        val store = FakeVectorStore(
            VectorStore.VectorEntry(
                id = "legacy-vector",
                vector = floatArrayOf(0.2f, 0.3f),
                metadata = legacyMetadata(),
            ),
        )
        val embedding = TestEmbeddingService()
        val result = VectorMemoryService(store, embedding).reindexLegacyVectors()

        assertEquals(1, result.scannedLegacy)
        assertEquals(1, result.migrated)
        assertEquals(0, result.skipped)
        assertEquals(1, embedding.batchCalls)
        assertNull(store.get("legacy-vector"))
        val replacement = store.getAllEntries().single()
        assertEquals("test:model:v2", replacement.metadata["embedding_model"])
        assertEquals("remember this detail", replacement.metadata["content"])
        assertEquals(2, replacement.vector.size)
    }

    @Test
    fun `keeps legacy entry when required identity metadata is missing`() = runBlocking {
        val store = FakeVectorStore(
            VectorStore.VectorEntry(
                id = "legacy-incomplete",
                vector = floatArrayOf(0.2f, 0.3f),
                metadata = mapOf("content" to "orphaned content"),
            ),
        )

        val result = VectorMemoryService(store, TestEmbeddingService()).reindexLegacyVectors()

        assertEquals(1, result.scannedLegacy)
        assertEquals(0, result.migrated)
        assertEquals(1, result.skipped)
        assertNotNull(store.get("legacy-incomplete"))
        assertEquals(1, store.entries.size)
    }

    @Test
    fun `keeps legacy entry when embedding batch fails`() = runBlocking {
        val store = FakeVectorStore(
            VectorStore.VectorEntry(
                id = "legacy-vector",
                vector = floatArrayOf(0.2f, 0.3f),
                metadata = legacyMetadata(),
            ),
        )
        val embedding = TestEmbeddingService(failBatch = true)

        try {
            VectorMemoryService(store, embedding).reindexLegacyVectors()
            throw AssertionError("Expected embedding failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("embedding failed"))
        }

        assertNotNull(store.get("legacy-vector"))
        assertEquals(1, store.entries.size)
    }

    @Test
    fun `concurrent newer write waits for legacy replacement and wins afterward`() = runBlocking {
        val store =
            FakeVectorStore(
                VectorStore.VectorEntry(
                    id = "legacy-vector",
                    vector = floatArrayOf(0.2f, 0.3f),
                    metadata = legacyMetadata(),
                ),
            )
        val embedding = BlockingBatchEmbeddingService()
        val service = VectorMemoryService(store, embedding)

        val reindex = async { service.reindexLegacyVectors() }
        embedding.batchStarted.await()

        val newerWrite =
            async {
                service.storeMessage(
                    content = "newer content",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                    messageId = "message-1",
                    contentType = "user_message",
                )
            }
        yield()
        assertFalse("same-identity write must wait while reindex owns write locks", newerWrite.isCompleted)

        embedding.allowBatch.complete(Unit)
        reindex.await()
        newerWrite.await()

        val replacement = store.getAllEntries().single()
        assertEquals("newer content", replacement.metadata["content"])
        assertEquals("test:model:v2", replacement.metadata["embedding_model"])
        assertNull(store.get("legacy-vector"))
    }

    private class BlockingBatchEmbeddingService : EmbeddingService {
        val batchStarted = CompletableDeferred<Unit>()
        val allowBatch = CompletableDeferred<Unit>()

        override suspend fun embed(text: String): FloatArray = floatArrayOf(0.7f, 0.8f)

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
            batchStarted.complete(Unit)
            allowBatch.await()
            return texts.map { floatArrayOf(1f, 0f) }
        }

        override fun getDimension(): Int = 2

        override fun modelId(): String = "test:model:v2"
    }

    private fun legacyMetadata(): Map<String, Any> =
        mapOf(
            "owner_id" to "owner",
            "character_id" to "character",
            "session_id" to "session",
            "message_id" to "message-1",
            "content_type" to "user_message",
            "content" to "remember this detail",
            "created_at" to "2026-10-15T00:00:00Z",
        )

    private class TestEmbeddingService(
        private val failBatch: Boolean = false,
    ) : EmbeddingService {
        var batchCalls = 0
            private set

        override suspend fun embed(text: String): FloatArray = floatArrayOf(1f, 0f)

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
            batchCalls++
            if (failBatch) error("embedding failed")
            return texts.map { floatArrayOf(1f, 0f) }
        }

        override fun getDimension(): Int = 2

        override fun modelId(): String = "test:model:v2"
    }

    private class FakeVectorStore(
        initial: VectorStore.VectorEntry,
    ) : VectorStore {
        val entries = linkedMapOf(initial.id to initial)

        override suspend fun add(id: String, vector: FloatArray, metadata: Map<String, Any>) {
            entries[id] = VectorStore.VectorEntry(id, vector, metadata)
        }

        override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
            entries.forEach { this.entries[it.id] = it }
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            filters: Map<String, Any>,
        ): List<VectorStore.SearchResult> = emptyList()

        override suspend fun get(id: String): VectorStore.VectorEntry? = entries[id]

        override suspend fun getAllEntries(): List<VectorStore.VectorEntry> = entries.values.toList()

        override suspend fun delete(id: String) {
            entries.remove(id)
        }

        override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
            val ids = entries.values.filter { entry ->
                filters.all { (key, value) -> entry.metadata[key] == value }
            }.map { it.id }
            ids.forEach(entries::remove)
            return ids.size
        }

        override suspend fun count(): Int = entries.size

        override suspend fun clear() {
            entries.clear()
        }
    }
}
