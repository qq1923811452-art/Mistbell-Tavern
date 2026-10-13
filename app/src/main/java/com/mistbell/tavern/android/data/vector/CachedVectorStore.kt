package com.mistbell.tavern.android.data.vector

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 带缓存的向量存储装饰器
 *
 * 优化策略：
 * - 按完整查询向量和过滤条件缓存搜索结果，避免仅使用哈希值导致碰撞误命中
 * - LRU 缓存策略，避免内存过度占用
 * - 写操作完成后使搜索缓存失效，并防止并发中的旧搜索结果重新写入缓存
 */
class CachedVectorStore(
    private val delegate: VectorStore,
    cacheSize: Int = 50,
) : VectorStore {
    private val searchCache = LruCache<SearchCacheKey, List<VectorStore.SearchResult>>(cacheSize.coerceAtLeast(1))
    private val cacheMutex = Mutex()
    private var cacheGeneration: Long = 0
    private var cacheHitCount: Long = 0
    private var cacheMissCount: Long = 0
    private var cacheWriteCount: Long = 0
    private var cacheInvalidationCount: Long = 0
    private val _cacheStatistics = MutableStateFlow(VectorCacheStatistics(0, 0, 0, 0, 0))
    val cacheStatistics: StateFlow<VectorCacheStatistics> = _cacheStatistics.asStateFlow()

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
    ): List<VectorStore.SearchResult> =
        if (topK <= 0) {
            emptyList()
        } else {
            val cacheKey =
                SearchCacheKey(
                    queryVector = queryVector.toList(),
                    topK = topK,
                    filters = filters.toMap(),
                )
            val lookup =
                cacheMutex.withLock {
                    val cached = searchCache.get(cacheKey)
                    if (cached == null) {
                        cacheMissCount++
                    } else {
                        cacheHitCount++
                    }
                    publishCacheStatistics()
                    cached to cacheGeneration
                }
            val cached = lookup.first
            if (cached != null) {
                Log.d(TAG, "Cache hit for search (dimension=" + queryVector.size + ")")
                cached
            } else {
                Log.d(TAG, "Cache miss for search (dimension=" + queryVector.size + ")")
                val results = delegate.search(queryVector, topK, filters)
                cacheMutex.withLock {
                    // If a write invalidated the cache while search was running, don't
                    // reinsert results computed against the pre-write store state.
                    if (lookup.second == cacheGeneration) {
                        searchCache.put(cacheKey, results.toList())
                        cacheWriteCount++
                        publishCacheStatistics()
                    }
                }
                results
            }
        }

    override suspend fun get(id: String): VectorStore.VectorEntry? {
        return delegate.get(id)
    }

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

    /**
     * Returns a consistent snapshot of cache operations under the cache mutex.
     * Searches with non-positive topK bypass the cache and are not counted.
     */
    suspend fun getCacheStatistics(): VectorCacheStatistics =
        cacheMutex.withLock { snapshotCacheStatistics() }

    private fun snapshotCacheStatistics(): VectorCacheStatistics =
        VectorCacheStatistics(
            hitCount = cacheHitCount,
            missCount = cacheMissCount,
            writeCount = cacheWriteCount,
            invalidationCount = cacheInvalidationCount,
            entryCount = searchCache.size(),
        )

    private fun publishCacheStatistics() {
        _cacheStatistics.value = snapshotCacheStatistics()
    }

    private suspend fun invalidateCache() {
        cacheMutex.withLock {
            cacheGeneration++
            cacheInvalidationCount++
            searchCache.evictAll()
            publishCacheStatistics()
        }
        Log.d(TAG, "Cache invalidated")
    }

    /**
     * 搜索缓存键使用向量快照，而不是单独使用 contentHashCode，
     * 以免不同查询向量发生哈希碰撞时返回错误的缓存结果。
     */
    private data class SearchCacheKey(
        val queryVector: List<Float>,
        val topK: Int,
        val filters: Map<String, Any>,
    )
}
