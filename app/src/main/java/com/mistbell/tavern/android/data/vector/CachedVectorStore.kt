package com.mistbell.tavern.android.data.vector

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 带缓存的向量存储装饰器
 *
 * 优化策略：
 * - 缓存搜索结果（基于查询向量哈希 + 过滤条件）
 * - LRU 缓存策略，避免内存过度占用
 * - 写操作自动清除相关缓存
 */
@Suppress("TooManyFunctions")
class CachedVectorStore(
    private val delegate: VectorStore,
    cacheSize: Int = 50,
) : VectorStore {
    private val searchCache = LruCache<SearchCacheKey, List<VectorStore.SearchResult>>(cacheSize)
    private val cacheMutex = Mutex()

    // Prevent an in-flight search from repopulating the cache after a write invalidates it.
    private var cacheGeneration: Long = 0L

    companion object {
        private const val TAG = "CachedVectorStore"
    }

    override suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        delegate.add(id, vector, metadata)
        invalidateCache()
    }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        delegate.addBatch(entries)
        invalidateCache()
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> {
        // Exact vector contents avoid collisions from contentHashCode-only cache keys.
        val cacheKey =
            SearchCacheKey(
                queryVector = QueryVectorKey(queryVector),
                topK = topK,
                // Preserve each value's equality semantics: the delegate compares metadataValue == value.
                // Normalizing arrays/iterables can merge cache keys that the delegate considers unequal.
                filters = filters.entries.sortedBy { it.key }.map { it.key to it.value },
            )

        val generation =
            cacheMutex.withLock {
                searchCache.get(cacheKey)?.let { cached ->
                    Log.d(TAG, "Cache hit for search")
                    return cached
                }
                cacheGeneration
            }

        Log.d(TAG, "Cache miss for search")
        val results = delegate.search(queryVector, topK, filters)

        // Do not cache stale results if a write/delete occurred during the search.
        cacheMutex.withLock {
            if (generation == cacheGeneration) {
                searchCache.put(cacheKey, results)
            }
        }

        return results
    }

    override suspend fun get(id: String): VectorStore.VectorEntry? {
        return delegate.get(id)
    }

    override suspend fun getAllEntries(): List<VectorStore.VectorEntry> = delegate.getAllEntries()

    override suspend fun delete(id: String) {
        delegate.delete(id)
        invalidateCache()
    }

    override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
        val count = delegate.deleteByFilters(filters)
        if (count > 0) {
            invalidateCache()
        }
        return count
    }

    override suspend fun count(): Int {
        return delegate.count()
    }

    override suspend fun clear() {
        delegate.clear()
        invalidateCache()
    }

    /** Public hook for provider/model reconfiguration. */
    suspend fun invalidateSearchCache() = invalidateCache()

    private suspend fun invalidateCache() {
        cacheMutex.withLock {
            cacheGeneration += 1
            searchCache.evictAll()
        }
        Log.d(TAG, "Cache invalidated")
    }

    /**
     * 搜索缓存键
     */
    private class QueryVectorKey(vector: FloatArray) {
        private val values = vector.copyOf()
        private val contentHash = values.contentHashCode()

        override fun hashCode(): Int = contentHash

        override fun equals(other: Any?): Boolean =
            other is QueryVectorKey && values.contentEquals(other.values)
    }

    private data class SearchCacheKey(
        val queryVector: QueryVectorKey,
        val topK: Int,
        val filters: List<Pair<String, Any?>>,
    )
}
