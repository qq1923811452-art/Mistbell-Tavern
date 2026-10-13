package com.mistbell.tavern.android.data.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VectorMemoryServiceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: InMemoryVectorStore

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            store = InMemoryVectorStore(context)
            store.clear()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            store.clear()
        }
    }

    @Test
    fun identicalStoreAndSearchTextUsesOneEmbeddingRequest() {
        runBlocking {
            val embedding = CountingEmbeddingService("test-model-v1", floatArrayOf(1f, 0f, 0f))
            val service = VectorMemoryService(store, embedding)

            service.storeMessage(
                content = "The user prefers concise replies",
                ownerId = "owner",
                characterId = "character",
                sessionId = "session",
                messageId = "message-1",
                contentType = "user_message",
            )
            val results =
                service.searchRelevantMemories(
                    query = "The user prefers concise replies",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                    topK = 5,
                )

            assertEquals(1, embedding.calls)
            assertEquals(1, results.size)
            assertEquals("test-model-v1", results.single().metadata["embedding_model"])
        }
    }

    @Test
    fun vectorsFromDifferentModelIdentitiesAreNeverMixed() {
        runBlocking {
            val original =
                VectorMemoryService(
                    store,
                    CountingEmbeddingService("model-a", floatArrayOf(1f, 0f, 0f)),
                )
            original.storeMessage(
                content = "A remembered fact",
                ownerId = "owner",
                characterId = "character",
                sessionId = "session",
                messageId = "message-2",
                contentType = "user_message",
            )

            val switched =
                VectorMemoryService(
                    store,
                    CountingEmbeddingService("model-b", floatArrayOf(1f, 0f, 0f)),
                )
            val results =
                switched.searchRelevantMemories(
                    query = "A remembered fact",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                    topK = 5,
                )

            assertTrue("Different embedding spaces must not return each other's vectors", results.isEmpty())
            val originalResults =
                original.searchRelevantMemories(
                    query = "A remembered fact",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                    topK = 5,
                )
            assertEquals(1, originalResults.size)
        }
    }

    @Test
    fun vectorStorePersistsEntriesAcrossInstances() {
        runBlocking {
            store.add(
                id = "persisted-vector",
                vector = floatArrayOf(1f, 0f),
                metadata =
                    mapOf(
                        "owner_id" to "owner",
                        "embedding_model" to "persist-model",
                        "content" to "persistent fact",
                    ),
            )

            val reopened = InMemoryVectorStore(context)
            val results =
                reopened.search(
                    queryVector = floatArrayOf(1f, 0f),
                    topK = 1,
                    filters = mapOf("owner_id" to "owner", "embedding_model" to "persist-model"),
                )

            assertEquals(1, results.size)
            assertEquals("persistent fact", results.single().content)
        }
    }

    private class CountingEmbeddingService(
        private val identity: String,
        private val vector: FloatArray,
    ) : EmbeddingService {
        var calls: Int = 0
            private set

        override fun modelId(): String = identity

        override suspend fun embed(text: String): FloatArray {
            calls++
            return vector.copyOf()
        }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

        override fun getDimension(): Int = vector.size
    }
}
