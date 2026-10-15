package com.mistbell.tavern.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.mistbell.tavern.android.di.AppContainer
import com.mistbell.tavern.android.util.SecureStore

class TavernApplication : Application() {
    /**
     * 应用级 DI 容器：所有服务的唯一装配入口。
     *
     * 取服务一律走 `instance.container.xxx`。
     *
     * 这里曾有一组同名的 `by lazy` 委托属性（database / vectorStore / embeddingService /
     * vectorMemoryService）用于兼容旧调用。它们已随迁移完成而删除，原因有二：
     * 1. 全仓（含 test/androidTest）已无任何引用；
     * 2. `by lazy` 会**永久缓存第一次创建的实例**，而 AppContainer 在运行时切换
     *    embedding 源时会发布新的一代（`@Volatile runtimeEmbeddingServices`）。
     *    因此保留别名等于埋一个「切换后仍读到旧一代服务」的陷阱。
     */
    val container: AppContainer by lazy { AppContainer(this) }

    companion object {
        private const val TAG = "TavernApplication"
        private const val PREFS_NAME = "tavern_settings"
        private const val KEY_EMBEDDING_API_KEY = "embedding_api_key"
        private const val KEY_EMBEDDING_BASE_URL = "embedding_base_url"

        lateinit var instance: TavernApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 移除同步初始化，改为完全延迟加载
        Log.d(TAG, "TavernApplication created (services will be initialized on demand)")
    }

    /**
     * 更新 Embedding API Key
     * 注意：由于服务使用 lazy 初始化，需要重启应用才能生效
     */
    fun updateEmbeddingApiKey(apiKey: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_EMBEDDING_API_KEY, SecureStore.wrap(apiKey)).apply()
        Log.d(TAG, "Embedding API key updated (restart required)")
    }

    /**
     * 更新 Embedding Base URL
     * 注意：由于服务使用 lazy 初始化，需要重启应用才能生效
     */
    fun updateEmbeddingBaseUrl(baseUrl: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_EMBEDDING_BASE_URL, baseUrl).apply()
        Log.d(TAG, "Embedding base URL updated (restart required)")
    }
}
