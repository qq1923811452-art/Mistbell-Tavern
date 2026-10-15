package com.mistbell.tavern.android.data.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CachedVectorStoreFilterCacheInstrumentedTest {
    private lateinit var delegate: InMemoryVectorStore
    private lateinit var cachedStore: CachedVectorStore

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            delegate = InMemoryVectorStore(context)
            delegate.clear()
            cachedStore = CachedVectorStore(delegate)
        }
    }

    @Test
    fun arrayFiltersKeepDelegateIdentityEqualityInsteadOfCollidingAfterNormalization() {
        runBlocking {
            val storedTag = intArrayOf(1, 2, 3)
            cachedStore.add(
                id = "array-filter-entry",
                vector = floatArrayOf(1f, 0f),
                metadata = mapOf("tag" to storedTag, "content" to "array-backed metadata"),
            )

            val matching =
                cachedStore.search(
                    queryVector = floatArrayOf(1f, 0f),
                    topK = 5,
                    filters = mapOf("tag" to storedTag),
                )
            val nonMatching =
                cachedStore.search(
                    queryVector = floatArrayOf(1f, 0f),
                    topK = 5,
                    filters = mapOf("tag" to intArrayOf(1, 2, 3)),
                )

            assertEquals(1, matching.size)
            assertEquals(0, nonMatching.size)
        }
    }

    @Test
    fun mutationFailureStillInvalidatesSearchCacheWhenDelegateAlreadyChanged() {
        runBlocking {
            val failingDelegate =
                object : VectorStore by delegate {
                    override suspend fun add(
                        id: String,
                        vector: FloatArray,
                        metadata: Map<String, Any>,
                    ) {
                        delegate.add(id, vector, metadata)
                        throw IllegalStateException("simulated failure after delegate mutation")
                    }
                }
            val store = CachedVectorStore(failingDelegate)
            val query = floatArrayOf(1f, 0f)

            assertEquals(0, store.search(query, 5, emptyMap()).size)
            try {
                store.add(
                    id = "partially-written-vector",
                    vector = query,
                    metadata = mapOf("content" to "visible after partial mutation"),
                )
                throw AssertionError("Expected simulated mutation failure")
            } catch (expected: IllegalStateException) {
                assertEquals("simulated failure after delegate mutation", expected.message)
            }

            assertEquals(1, store.search(query, 5, emptyMap()).size)
        }
    }
}
