package com.mistbell.tavern.android.data.vector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class VectorMemoryServiceCancellationTest {
    @Test
    fun `search propagates coroutine cancellation`() =
        runBlocking {
            val cancellation = CancellationException("search cancelled")
            val service = VectorMemoryService(TestVectorStore("search", cancellation), TestEmbeddingService())

            assertCancellation(cancellation) {
                service.searchRelevantMemories("query", "owner", "character", null)
            }
        }

    @Test
    fun `delete propagates coroutine cancellation`() =
        runBlocking {
            val cancellation = CancellationException("delete cancelled")
            val service = VectorMemoryService(TestVectorStore("deleteByFilters", cancellation), TestEmbeddingService())

            assertCancellation(cancellation) {
                service.deleteSessionVectors("owner", "character", "session")
            }
        }

    @Test
    fun `statistics propagates coroutine cancellation`() =
        runBlocking {
            val cancellation = CancellationException("count cancelled")
            val service = VectorMemoryService(TestVectorStore("count", cancellation), TestEmbeddingService())

            assertCancellation(cancellation) {
                service.getStatistics()
            }
        }

    private suspend fun assertCancellation(
        expected: CancellationException,
        action: suspend () -> Unit,
    ) {
        try {
            action()
            fail("Expected coroutine cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    private class TestEmbeddingService : EmbeddingService {
        override suspend fun embed(text: String): FloatArray = floatArrayOf(1f)

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(1f) }

        override fun getDimension(): Int = 1

        override fun modelId(): String = "test:model"
    }

    private class TestVectorStore(
        private val failOn: String,
        private val cancellation: CancellationException,
    ) : VectorStore {
        private fun cancelIf(operation: String) {
            if (operation == failOn) throw cancellation
        }

        override suspend fun add(
            id: String,
            vector: FloatArray,
            metadata: Map<String, Any>,
        ) = Unit

        override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) = Unit

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            filters: Map<String, Any>,
        ): List<VectorStore.SearchResult> {
            cancelIf("search")
            return emptyList()
        }

        override suspend fun get(id: String): VectorStore.VectorEntry? = null

        override suspend fun getAllEntries(): List<VectorStore.VectorEntry> = emptyList()

        override suspend fun delete(id: String) = Unit

        override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
            cancelIf("deleteByFilters")
            return 0
        }

        override suspend fun count(): Int {
            cancelIf("count")
            return 0
        }

        override suspend fun clear() = Unit
    }
}
