package com.mistbell.tavern.android.data.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
    fun legacyVectorsAreUsedOnlyWhenTheEmbeddingSpaceIsKnownCompatible() {
        runBlocking {
            store.add(
                id = "legacy-vector",
                vector = floatArrayOf(1f, 0f, 0f),
                metadata =
                    mapOf(
                        "owner_id" to "owner",
                        "character_id" to "character",
                        "session_id" to "session",
                        "content" to "legacy fact",
                    ),
            )

            val compatible =
                VectorMemoryService(
                    store,
                    CountingEmbeddingService(
                        identity = "known-default",
                        vector = floatArrayOf(1f, 0f, 0f),
                        legacyCompatible = true,
                    ),
                )
            val compatibleResults =
                compatible.searchRelevantMemories(
                    query = "legacy fact",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                )
            assertEquals(1, compatibleResults.size)

            val incompatible =
                VectorMemoryService(
                    store,
                    CountingEmbeddingService(
                        identity = "custom-model",
                        vector = floatArrayOf(1f, 0f, 0f),
                    ),
                )
            val incompatibleResults =
                incompatible.searchRelevantMemories(
                    query = "legacy fact",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                )
            assertTrue(
                "Unknown legacy vector space must not be mixed into custom models",
                incompatibleResults.isEmpty(),
            )
        }
    }

    @Test
    fun corruptedPersistedStoreSurfacesDiagnosticWithoutOverwritingFile() {
        runBlocking {
            val storageFile = File(context.filesDir, "vector_store.json")
            storageFile.writeText("{not-valid-json")

            val corruptedStore = InMemoryVectorStore(context)
            val service =
                VectorMemoryService(
                    corruptedStore,
                    CountingEmbeddingService("test-model", floatArrayOf(1f, 0f, 0f)),
                )

            val results =
                service.searchRelevantMemories(
                    query = "remembered fact",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = null,
                )

            assertTrue("Corrupt storage must not be interpreted as valid results", results.isEmpty())
            assertNotNull("Settings should receive a storage-corruption diagnostic", service.storageError.value)
            assertEquals("Corrupt file contents must remain available for recovery", "{not-valid-json", storageFile.readText())
        }
    }

    @Test
    fun confirmedRecoveryQuarantinesCorruptFileAndCreatesEmptyStore() {
        runBlocking {
            val storageFile = File(context.filesDir, "vector_store.json")
            val corruptContents = "{not-valid-json"
            storageFile.writeText(corruptContents)

            assertTrue("Recovery should run only after explicit invocation", store.recoverCorruptStorage())

            val quarantined =
                context.filesDir.listFiles()?.firstOrNull {
                    it.name.startsWith("vector_store.corrupt-") && it.name.endsWith(".json")
                }
            assertNotNull("The original corrupt file must be preserved", quarantined)
            assertEquals(corruptContents, quarantined!!.readText())
            assertTrue("Recovery should create a fresh persisted store", storageFile.exists())
            assertEquals(0, InMemoryVectorStore(context).count())
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
        private val legacyCompatible: Boolean = false,
    ) : EmbeddingService {
        var calls: Int = 0
            private set

        override fun modelId(): String = identity

        override fun supportsLegacyVectors(): Boolean = legacyCompatible

        override suspend fun embed(text: String): FloatArray {
            calls++
            return vector.copyOf()
        }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

        override fun getDimension(): Int = vector.size
    }
}
