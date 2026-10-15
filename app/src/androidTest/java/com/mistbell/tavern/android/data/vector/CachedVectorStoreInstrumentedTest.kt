package com.mistbell.tavern.android.data.vector

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CachedVectorStoreInstrumentedTest {
    private lateinit var delegate: InMemoryVectorStore
    private lateinit var cachedStore: CachedVectorStore

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            delegate = InMemoryVectorStore(context)
            delegate.clear()
            cachedStore = CachedVectorStore(delegate)
        }
    }

    @Test
    fun repeatedIdenticalSearchCountsOneMissThenOneHit() {
        runBlocking {
            val query = floatArrayOf(1f, 0f, 0f)
            val filters = mapOf("owner_id" to "owner")

            cachedStore.search(query, 5, filters)
            cachedStore.search(query, 5, filters)

            val stats = cachedStore.cacheStatistics.value
            assertEquals(1L, stats.missCount)
            assertEquals(1L, stats.hitCount)
            assertEquals(2L, stats.totalLookups)
            assertEquals(0.5, stats.hitRate, 1e-9)
        }
    }

    @Test
    fun addingVectorInvalidatesSearchCacheAndNextLookupMisses() {
        runBlocking {
            val query = floatArrayOf(1f, 0f, 0f)
            val filters = mapOf("owner_id" to "owner")
            cachedStore.search(query, 5, filters)
            cachedStore.search(query, 5, filters)

            cachedStore.add(
                id = "new-vector",
                vector = query,
                metadata = mapOf("owner_id" to "owner", "content" to "new fact"),
            )
            val results = cachedStore.search(query, 5, filters)

            val stats = cachedStore.cacheStatistics.value
            assertEquals(1, results.size)
            assertEquals(2L, stats.missCount)
            assertEquals(1L, stats.hitCount)
            assertEquals(1L, stats.invalidationCount)
        }
    }

    @Test
    fun nonPositiveTopKDoesNotCountAsCacheLookup() {
        runBlocking {
            val results = cachedStore.search(floatArrayOf(1f), 0, emptyMap())

            assertEquals(0, results.size)
            assertEquals(0L, cachedStore.cacheStatistics.value.totalLookups)
        }
    }
}
