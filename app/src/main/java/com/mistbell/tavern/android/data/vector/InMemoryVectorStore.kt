package com.mistbell.tavern.android.data.vector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 内存向量存储实现（带持久化和内存限制）
 *
 * 移植自后端 InMemoryVectorStore.java
 *
 * 特点：
 * - 内存存储，快速检索
 * - 自动持久化到磁盘
 * - 支持过滤条件
 * - 余弦相似度计算
 * - 内存限制：最多保留 1000 个向量
 */
class InMemoryVectorStore(
    private val context: Context,
    private val maxVectorsInMemory: Int = 1000,
) : VectorStore {
    private val vectors = mutableMapOf<String, VectorStore.VectorEntry>()
    private val capacity = maxVectorsInMemory.coerceAtLeast(1)
    private val mutex = Mutex()
    private val storageFile = File(context.filesDir, "vector_store.json")

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    companion object {
        private const val TAG = "InMemoryVectorStore"
    }

    init {
        // 延迟加载：只在需要时才从磁盘加载
        // loadFromDisk() 移到首次使用时调用
    }

    private var isLoaded = false

    private suspend fun ensureLoaded() {
        if (!isLoaded) {
            loadFromDisk()
            isLoaded = true
        }
    }

    override suspend fun add(
        id: String,
        vector: FloatArray,
        metadata: Map<String, Any>,
    ) {
        mutex.withLock {
            ensureLoaded()
            vectors[id] = VectorStore.VectorEntry(id, vector, metadata)

            // 内存限制：如果超过最大数量，移除最旧的条目
            while (vectors.size > capacity) {
                val oldestKey = vectors.keys.first()
                vectors.remove(oldestKey)
                Log.d(TAG, "Memory limit reached, removed oldest vector: $oldestKey")
            }

            saveToDisk()
        }
        Log.d(TAG, "Added vector: $id (dimension: ${vector.size})")
    }

    override suspend fun addBatch(entries: List<VectorStore.VectorEntry>) {
        mutex.withLock {
            ensureLoaded()
            entries.forEach { entry ->
                vectors[entry.id] = entry
            }

            // 内存限制：保留最新的 maxVectorsInMemory 个
            if (vectors.size > capacity) {
                val toRemove = vectors.size - capacity
                vectors.keys.take(toRemove).forEach { vectors.remove(it) }
                Log.d(TAG, "Memory limit reached, removed $toRemove oldest vectors")
            }

            saveToDisk()
        }
        Log.d(TAG, "Added ${entries.size} vectors in batch")
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        filters: Map<String, Any>,
    ): List<VectorStore.SearchResult> {
        if (topK <= 0) return emptyList()

        return withContext(Dispatchers.Default) {
            mutex.withLock {
                ensureLoaded()
                vectors.values
                    .filter { entry -> matchesFilters(entry, filters) }
                    .map { entry ->
                        val score = VectorUtils.cosineSimilarity(queryVector, entry.vector)
                        VectorStore.SearchResult(
                            id = entry.id,
                            score = score,
                            content = entry.metadata["content"] as? String ?: "",
                            metadata = entry.metadata,
                        )
                    }
                    .sortedByDescending { it.score }
                    .take(topK)
            }
        }
    }

    override suspend fun get(id: String): VectorStore.VectorEntry? {
        return mutex.withLock {
            ensureLoaded()
            vectors[id]
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            ensureLoaded()
            vectors.remove(id)
            saveToDisk()
        }
        Log.d(TAG, "Deleted vector: $id")
    }

    override suspend fun deleteByFilters(filters: Map<String, Any>): Int {
        val deleted =
            mutex.withLock {
                ensureLoaded()
                val toDelete =
                    vectors.values.filter { entry ->
                        matchesFilters(entry, filters)
                    }.map { it.id }

                toDelete.forEach { id ->
                    vectors.remove(id)
                }

                if (toDelete.isNotEmpty()) {
                    saveToDisk()
                }

                toDelete.size
            }

        Log.d(TAG, "Deleted $deleted vectors by filters")
        return deleted
    }

    override suspend fun count(): Int {
        return mutex.withLock {
            ensureLoaded()
            vectors.size
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            vectors.clear()
            isLoaded = true // 保持加载状态，避免重新加载
            saveToDisk()
        }
        Log.d(TAG, "Cleared all vectors")
    }

    /**
     * 检查向量条目是否匹配过滤条件
     */
    private fun matchesFilters(
        entry: VectorStore.VectorEntry,
        filters: Map<String, Any>,
    ): Boolean {
        if (filters.isEmpty()) return true

        return filters.all { (key, value) ->
            val metadataValue = entry.metadata[key]
            metadataValue == value
        }
    }

    /**
     * 持久化到磁盘
     */
    private fun saveToDisk() {
        try {
            val data =
                VectorStoreData(
                    vectors =
                        vectors.values.map { entry ->
                            SerializableVectorEntry(
                                id = entry.id,
                                vector = entry.vector.toList(),
                                metadata = entry.metadata.mapValues { (_, v) -> v.toString() },
                            )
                        },
                )

            storageFile.writeText(json.encodeToString(data))
            Log.d(TAG, "Saved ${vectors.size} vectors to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save vectors to disk: ${e.message}", e)
        }
    }

    /**
     * 从磁盘加载
     */
    private fun loadFromDisk() {
        if (!storageFile.exists()) {
            Log.d(TAG, "No existing vector store file")
            return
        }

        try {
            val content = storageFile.readText()
            val data = json.decodeFromString<VectorStoreData>(content)

            data.vectors.forEach { entry ->
                vectors[entry.id] =
                    VectorStore.VectorEntry(
                        id = entry.id,
                        vector = entry.vector.toFloatArray(),
                        metadata = entry.metadata,
                    )
            }

            // Older files may exceed the configured limit. Keep the newest
            // entries in file order, matching add/addBatch eviction behavior.
            if (vectors.size > capacity) {
                val toRemove = vectors.size - capacity
                vectors.keys.take(toRemove).forEach { vectors.remove(it) }
                // Persist the trimmed collection so oversized legacy files do
                // not keep consuming disk space and get reprocessed on launch.
                saveToDisk()
                Log.d(TAG, "Memory limit reached during load, removed $toRemove oldest vectors")
            }

            Log.d(TAG, "Loaded ${vectors.size} vectors from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vectors from disk: ${e.message}", e)
        }
    }

    @Serializable
    private data class VectorStoreData(
        val vectors: List<SerializableVectorEntry>,
    )

    @Serializable
    private data class SerializableVectorEntry(
        val id: String,
        val vector: List<Float>,
        val metadata: Map<String, String>,
    )
}
