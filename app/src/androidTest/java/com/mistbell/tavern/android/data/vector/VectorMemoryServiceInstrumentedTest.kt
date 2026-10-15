package com.mistbell.tavern.android.data.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

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
    fun differentEmbeddingTextsCanBeGeneratedConcurrently() =
        runBlocking {
            val modelId = "concurrency-test-model"
            val texts = textsOnDifferentCacheStripes(modelId)
            val embedding = ConcurrentEmbeddingService(modelId)
            val service = VectorMemoryService(store, embedding)

            listOf(texts.first, texts.second).map { text ->
                async {
                    service.searchRelevantMemories(
                        query = text,
                        ownerId = "owner",
                        characterId = "character",
                        sessionId = null,
                    )
                }
            }.forEach { it.await() }

            assertTrue(
                "Independent embedding requests should overlap instead of sharing one global lock",
                embedding.maxConcurrent.get() >= 2,
            )
        }

    @Test
    fun concurrentRequestsForSameEmbeddingTextShareOneGeneration() =
        runBlocking {
            val modelId = "same-text-concurrency-model"
            val embedding = ConcurrentEmbeddingService(modelId)
            val service = VectorMemoryService(store, embedding)

            listOf("same query", "same query").map { text ->
                async {
                    service.searchRelevantMemories(
                        query = text,
                        ownerId = "owner",
                        characterId = "character",
                        sessionId = null,
                    )
                }
            }.forEach { it.await() }

            assertEquals("Same-text concurrent misses must share one embedding request", 1, embedding.calls.get())
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
    fun legacyFallbackKeepsCompatibleVectorsAndExcludesOtherModels() =
        runBlocking {
            val recordingStore = RecordingVectorStore(store)
            recordingStore.addBatch(
                listOf(
                    VectorStore.VectorEntry(
                        id = "current-model",
                        vector = floatArrayOf(1f, 0f, 0f),
                        metadata =
                            mapOf(
                                "owner_id" to "owner",
                                "character_id" to "character",
                                "session_id" to "session",
                                "embedding_model" to "known-default",
                                "content" to "current fact",
                            ),
                    ),
                    VectorStore.VectorEntry(
                        id = "legacy-compatible",
                        vector = floatArrayOf(0.99f, 0.01f, 0f),
                        metadata =
                            mapOf(
                                "owner_id" to "owner",
                                "character_id" to "character",
                                "session_id" to "session",
                                "content" to "legacy fact",
                            ),
                    ),
                    VectorStore.VectorEntry(
                        id = "different-model",
                        vector = floatArrayOf(1f, 0f, 0f),
                        metadata =
                            mapOf(
                                "owner_id" to "owner",
                                "character_id" to "character",
                                "session_id" to "session",
                                "embedding_model" to "unrelated-model",
                                "content" to "unrelated fact",
                            ),
                    ),
                ),
            )

            val service =
                VectorMemoryService(
                    recordingStore,
                    CountingEmbeddingService(
                        identity = "known-default",
                        vector = floatArrayOf(1f, 0f, 0f),
                        legacyCompatible = true,
                    ),
                )

            val results =
                service.searchRelevantMemories(
                    query = "query",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                    topK = 2,
                )

            assertEquals(setOf("current-model", "legacy-compatible"), results.map { it.id }.toSet())
            assertEquals(
                "The current-model query should fall back only because it cannot fill top-K",
                2,
                recordingStore.searchCalls.size,
            )
            assertEquals(2, recordingStore.searchCalls[0].topK)
            assertTrue(recordingStore.searchCalls[0].filters.containsKey("embedding_model"))
            assertEquals(3, recordingStore.searchCalls[1].topK)
            assertFalse(recordingStore.searchCalls[1].filters.containsKey("embedding_model"))
        }

    @Test
    fun legacyFallbackIsSkippedWhenCurrentModelAlreadyFillsTopK() =
        runBlocking {
            val recordingStore = RecordingVectorStore(store)
            recordingStore.addBatch(
                listOf(
                    VectorStore.VectorEntry(
                        id = "current-1",
                        vector = floatArrayOf(1f, 0f, 0f),
                        metadata =
                            mapOf(
                                "owner_id" to "owner",
                                "character_id" to "character",
                                "session_id" to "session",
                                "embedding_model" to "known-default",
                                "content" to "current fact one",
                            ),
                    ),
                    VectorStore.VectorEntry(
                        id = "current-2",
                        vector = floatArrayOf(0.99f, 0.01f, 0f),
                        metadata =
                            mapOf(
                                "owner_id" to "owner",
                                "character_id" to "character",
                                "session_id" to "session",
                                "embedding_model" to "known-default",
                                "content" to "current fact two",
                            ),
                    ),
                    VectorStore.VectorEntry(
                        id = "legacy-compatible",
                        vector = floatArrayOf(1f, 0f, 0f),
                        metadata =
                            mapOf(
                                "owner_id" to "owner",
                                "character_id" to "character",
                                "session_id" to "session",
                                "content" to "legacy fact",
                            ),
                    ),
                ),
            )

            val service =
                VectorMemoryService(
                    recordingStore,
                    CountingEmbeddingService(
                        identity = "known-default",
                        vector = floatArrayOf(1f, 0f, 0f),
                        legacyCompatible = true,
                    ),
                )

            val results =
                service.searchRelevantMemories(
                    query = "query",
                    ownerId = "owner",
                    characterId = "character",
                    sessionId = "session",
                    topK = 2,
                )

            assertEquals(2, results.size)
            assertTrue(results.all { it.metadata["embedding_model"] == "known-default" })
            assertEquals(
                "A full current-model result must not trigger the broad legacy scan",
                1,
                recordingStore.searchCalls.size,
            )
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

            val quarantineDirectory = File(context.filesDir, "vector_store_quarantine")
            val quarantined =
                quarantineDirectory.listFiles()?.firstOrNull {
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

    private fun textsOnDifferentCacheStripes(modelId: String): Pair<String, String> {
        val firstByStripe = mutableMapOf<Int, String>()
        for (index in 0 until 100) {
            val text = "parallel-embedding-$index"
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest("$modelId\u0000$text".toByteArray(StandardCharsets.UTF_8))
            val key = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val stripe = Math.floorMod(key.hashCode(), 32)
            val other = firstByStripe.entries.firstOrNull { it.key != stripe }
            if (other != null) return other.value to text
            firstByStripe.putIfAbsent(stripe, text)
        }
        error("Could not find two distinct embedding-cache stripes")
    }

    private class ConcurrentEmbeddingService(
        private val identity: String,
    ) : EmbeddingService {
        private val active = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val calls = AtomicInteger()

        override fun modelId(): String = identity

        override suspend fun embed(text: String): FloatArray {
            calls.incrementAndGet()
            val current = active.incrementAndGet()
            maxConcurrent.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(100)
                return floatArrayOf(1f, 0f, 0f)
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

        override fun getDimension(): Int = 3
    }

    private class RecordingVectorStore(
        private val delegate: VectorStore,
    ) : VectorStore {
        data class SearchCall(val topK: Int, val filters: Map<String, Any>)

        val searchCalls = mutableListOf<SearchCall>()

        override suspend fun add(
            id: String,
            vector: FloatArray,
            metadata: Map<String, Any>,
        ) {
            delegate.add(id, vector, metadata)
        }

        override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
            delegate.addBatch(entries)
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            filters: Map<String, Any>,
        ): List<VectorStore.SearchResult> {
            searchCalls += SearchCall(topK, filters.toMap())
            return delegate.search(queryVector, topK, filters)
        }

        override suspend fun get(id: String): VectorStore.VectorEntry? = delegate.get(id)

        override suspend fun delete(id: String) = delegate.delete(id)

        override suspend fun deleteByFilters(filters: Map<String, Any>): Int = delegate.deleteByFilters(filters)

        override suspend fun count(): Int = delegate.count()

        override suspend fun recoverCorruptStorage(): Boolean = delegate.recoverCorruptStorage()

        override suspend fun clear() = delegate.clear()
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
