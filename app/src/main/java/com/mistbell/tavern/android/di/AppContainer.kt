package com.mistbell.tavern.android.di

import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.vector.CachedVectorStore
import com.mistbell.tavern.android.data.vector.EmbeddingService
import com.mistbell.tavern.android.data.vector.EmbeddingGemma2LocalEmbeddingService
import com.mistbell.tavern.android.data.vector.InMemoryVectorStore
import com.mistbell.tavern.android.data.vector.LocalEmbeddingService
import com.mistbell.tavern.android.data.vector.MockEmbeddingService
import com.mistbell.tavern.android.data.vector.OpenAIEmbeddingService
import com.mistbell.tavern.android.data.vector.VectorCacheStatistics
import com.mistbell.tavern.android.data.vector.VectorMemoryService
import com.mistbell.tavern.android.data.vector.VectorStore
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用级 DI 容器：集中装配 database / vectorStore / embeddingService / vectorMemoryService。
 *
 * 各服务均延迟初始化；装配失败按原语义回退（向量服务回退内存实现，embedding 回退 Mock），
 * 绝不因装配崩溃阻塞应用启动。
 */
@Suppress("TooGenericExceptionCaught")
class AppContainer(private val appContext: Context) {
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(appContext)
    }

    val vectorStore: VectorStore by lazy {
        try {
            val baseStore = InMemoryVectorStore(appContext)
            CachedVectorStore(baseStore, cacheSize = 50).also {
                Log.d(TAG, "Vector store initialized with cache (lazy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vector store: ${e.message}", e)
            val baseStore = InMemoryVectorStore(appContext)
            CachedVectorStore(baseStore, cacheSize = 50)
        }
    }

    /**
     * Live hit/miss statistics for the app's cached vector search store.
     */
    val vectorCacheStatistics: StateFlow<VectorCacheStatistics> by lazy {
        (vectorStore as? CachedVectorStore)?.cacheStatistics
            ?: MutableStateFlow(VectorCacheStatistics(0, 0, 0, 0, 0))
    }

    /**
     * Quarantine the unreadable vector-store file, then publish a fresh service pair so
     * the settings diagnostic clears only after recovery has succeeded.
     */
    suspend fun recoverCorruptVectorStorage(): Boolean {
        val previousMemoryService = vectorMemoryService
        val recovered = vectorStore.recoverCorruptStorage()
        if (recovered) {
            previousMemoryService.storageErrorState.value = null
            val service = embeddingService
            runtimeEmbeddingServices = RuntimeEmbeddingServices(service, VectorMemoryService(vectorStore, service))
        }
        return recovered
    }

    /**
     * Embedding 服务（延迟初始化，源路由：settings `embedding_source`）
     *
     * - auto（默认）：有 embedding API key → OpenAI；无 key → Mock（词法回退）——兼容历史行为；
     * - api：强制 API（无 key 则 Mock）；
     * - local：本地 ONNX（bge-small-zh）；local-gemma：LiteRT-LM EmbeddingGemma 2；本地初始化失败时均回退 Mock，不调用远程 API。
     */
    private data class RuntimeEmbeddingServices(
        val embeddingService: EmbeddingService,
        val vectorMemoryService: VectorMemoryService,
    )

    @Volatile
    private var runtimeEmbeddingServices: RuntimeEmbeddingServices? = null

    private val initialEmbeddingService: EmbeddingService by lazy {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            assembleEmbeddingService()
        }
    }

    val embeddingService: EmbeddingService
        get() = runtimeEmbeddingServices?.embeddingService ?: initialEmbeddingService

    /**
     * Rebuild both services after the user changes embedding source.
     * The vector store is retained, but retrieval is filtered by embedding model identity so
     * vectors produced by incompatible models are never compared. Existing cache statistics survive.
     * Returns true only when a real local or remote embedding implementation is active.
     */
    suspend fun refreshEmbeddingSource(source: String): Boolean {
        val service = assembleEmbeddingService(source)
        val memoryService = VectorMemoryService(vectorStore, service)
        // Publish the matching pair atomically so concurrent callers cannot observe mixed generations.
        runtimeEmbeddingServices = RuntimeEmbeddingServices(service, memoryService)
        Log.i(TAG, "Embedding source switched: requested=$source, active=${service::class.java.simpleName}")
        return service.isSemantic
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun assembleEmbeddingService(sourceOverride: String? = null): EmbeddingService {
        val source =
            sourceOverride ?: runCatching {
                database.settingsDao().getValue("embedding_source")
            }.getOrNull()
        return try {
            when (source) {
                "local" -> {
                    Log.d(TAG, "Embedding source=local: loading bge-small-zh (ONNX)")
                    try {
                        LocalEmbeddingService.create(appContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Local ONNX embedding init failed; remote fallback is disabled", e)
                        MockEmbeddingService()
                    }
                }
                "local-gemma" -> {
                    Log.d(TAG, "Embedding source=local-gemma: loading EmbeddingGemma 2 with LiteRT-LM")
                    try {
                        EmbeddingGemma2LocalEmbeddingService.create(appContext)
                    } catch (e: LinkageError) {
                        Log.e(TAG, "LiteRT-LM native runtime is unavailable on this device; remote fallback is disabled", e)
                        MockEmbeddingService()
                    } catch (e: Exception) {
                        Log.e(TAG, "Local EmbeddingGemma 2 init failed; remote fallback is disabled", e)
                        MockEmbeddingService()
                    }
                }
                "api" -> openAiEmbeddingOrMock()
                else ->
                    if (getEmbeddingApiKey().isNotBlank()) {
                        openAiEmbeddingOrMock()
                    } else {
                        MockEmbeddingService()
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding service: ${e.message}", e)
            MockEmbeddingService()
        }
    }

    private fun openAiEmbeddingOrMock(): EmbeddingService {
        val apiKey = getEmbeddingApiKey()
        val baseUrl = getEmbeddingBaseUrl()
        return if (apiKey.isNotBlank()) {
            Log.d(TAG, "Using OpenAI Embedding Service (lazy)")
            OpenAIEmbeddingService(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = getEmbeddingModel(),
            )
        } else {
            // 无 key：不用伪向量，注入 Mock 占位（available=false，调用方走词法回退）
            Log.d(TAG, "No embedding API key, vector memory unavailable (lexical fallback)")
            MockEmbeddingService()
        }
    }

    private val initialVectorMemoryService: VectorMemoryService by lazy {
        try {
            VectorMemoryService(
                vectorStore = vectorStore,
                embeddingService = embeddingService,
            ).also {
                Log.d(TAG, "Vector memory service initialized (lazy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vector memory service: ${e.message}", e)
            VectorMemoryService(vectorStore, embeddingService)
        }
    }

    val vectorMemoryService: VectorMemoryService
        get() = runtimeEmbeddingServices?.vectorMemoryService ?: initialVectorMemoryService

    private fun getEmbeddingApiKey(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SecureStore.unwrap(prefs.getString(KEY_EMBEDDING_API_KEY, "") ?: "")
    }

    private fun getEmbeddingBaseUrl(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EMBEDDING_BASE_URL, "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"
    }

    private fun getEmbeddingModel(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EMBEDDING_MODEL, "text-embedding-3-small")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "text-embedding-3-small"
    }

    companion object {
        private const val TAG = "AppContainer"
        private const val PREFS_NAME = "tavern_settings"
        private const val KEY_EMBEDDING_API_KEY = "embedding_api_key"
        private const val KEY_EMBEDDING_BASE_URL = "embedding_base_url"
        private const val KEY_EMBEDDING_MODEL = "embedding_model"
    }
}
