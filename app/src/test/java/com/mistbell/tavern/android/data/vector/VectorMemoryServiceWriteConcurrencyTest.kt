package com.mistbell.tavern.android.data.vector

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VectorMemoryServiceWriteConcurrencyTest {
    @Test
    fun `concurrent writes to the same logical message persist one vector`() =
        runBlocking {
            val store = FakeVectorStore()
            val embedding = CountingEmbeddingService()
            val service = VectorMemoryService(store, embedding)

            val first =
                async {
                    service.storeMessage(
                        "same content",
                        "owner",
                        "character",
                        "session",
                        "message",
                        "summary",
                    )
                }
            val second =
                async {
                    service.storeMessage(
                        "same content",
                        "owner",
                        "character",
                        "session",
                        "message",
                        "summary",
                    )
                }

            assertEquals(first.await(), second.await())
            assertEquals(1, store.addCalls.get())
            assertEquals(1, store.entries.size)
            assertEquals(1, embedding.embedCalls.get())
        }

    private class CountingEmbeddingService : EmbeddingService {
        val embedCalls = AtomicInteger()

        override suspend fun embed(text: String): FloatArray {
            embedCalls.incrementAndGet()
            delay(30)
            return floatArrayOf(1f, 0f)
        }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

        override fun getDimension(): Int = 2

        override fun modelId(): String = "test:concurrency-model"
    }

    private class FakeVectorStore : VectorStore {
        val entries = linkedMapOf<String, VectorStore.VectorEntry>()
        val addCalls = AtomicInteger()
        private val mutex = Mutex()

        override suspend fun add(
            id: String,
            vector: FloatArray,
            metadata: Map<String, Any>,
        ) {
            mutex.withLock {
                addCalls.incrementAndGet()
                entries[id] = VectorStore.VectorEntry(id, vector.copyOf(), metadata.toMap())
            }
        }

        override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
            mutex.withLock { entries.forEach { this.entries[it.id] = it } }
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            filters: Map<String, Any>,
        ): List<VectorStore.SearchResult> = emptyList()

        override suspend fun get(id: String): VectorStore.VectorEntry? = mutex.withLock { entries[id] }

        override suspend fun getAllEntries(): List<VectorStore.VectorEntry> = mutex.withLock { entries.values.toList() }

        override suspend fun delete(id: String) {
            mutex.withLock { entries.remove(id) }
        }

        override suspend fun deleteByFilters(filters: Map<String, Any>): Int =
            mutex.withLock {
                val matchingIds =
                    entries.values.filter { entry ->
                        filters.all { (key, value) -> entry.metadata[key] == value }
                    }.map { it.id }
                matchingIds.forEach(entries::remove)
                matchingIds.size
            }

        override suspend fun count(): Int = mutex.withLock { entries.size }

        override suspend fun clear() {
            mutex.withLock { entries.clear() }
        }
    }
}
