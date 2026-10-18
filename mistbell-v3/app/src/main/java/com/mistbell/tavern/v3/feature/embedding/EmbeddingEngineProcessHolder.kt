package com.mistbell.tavern.v3.feature.embedding

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Owns one initialized native engine for the process, independent of Activity lifecycle. */
object EmbeddingEngineProcessHolder : ComponentCallbacks2 {
    private data class EngineKey(
        val canonicalPath: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val npuModelCompatible: Boolean,
    )
    private data class Entry(val key: EngineKey, val engine: LiteRtLmEmbeddingGemma2Engine)

    private val mutex = Mutex()
    private val stateLock = Any()
    @Volatile private var current: Entry? = null
    @Volatile private var registeredContext: Context? = null
    private var memoryGeneration = 0L

    suspend fun acquire(
        context: Context,
        modelFile: File,
        npuModelCompatible: Boolean,
        diagnostics: DiagnosticLogStore = DiagnosticLogStore(context.applicationContext.filesDir),
    ): LiteRtLmEmbeddingGemma2Engine {
        val appContext = context.applicationContext
        registerCallbacks(appContext)
        val key = EngineKey(modelFile.canonicalPath, modelFile.length(), modelFile.lastModified(), npuModelCompatible)
        mutex.lock()
        try {
            synchronized(stateLock) {
                current?.takeIf { it.key == key }?.let { return it.engine }
            }
            closeCurrent()
            val generationAtStart = synchronized(stateLock) { memoryGeneration }
            val engine = withContext(Dispatchers.IO) {
                LiteRtLmEmbeddingGemma2Engine(
                    context = appContext,
                    modelFile = modelFile,
                    npuModelCompatible = npuModelCompatible,
                    diagnostics = diagnostics,
                )
            }
            try {
                engine.initialize()
            } catch (failure: Throwable) {
                runCatching { engine.close() }
                throw failure
            }
            val retained = synchronized(stateLock) {
                if (generationAtStart != memoryGeneration) false
                else {
                    current = Entry(key, engine)
                    true
                }
            }
            if (!retained) {
                runCatching { engine.close() }
                throw IllegalStateException("Embedding engine was released during initialization; retry after memory pressure.")
            }
            return engine
        } finally {
            mutex.unlock()
        }
    }

    fun close() {
        synchronized(stateLock) { memoryGeneration++ }
        closeCurrent()
    }

    private fun closeCurrent() {
        val old = synchronized(stateLock) { current.also { current = null } }
        old?.let { runCatching { it.engine.close() } }
    }

    private fun registerCallbacks(context: Context) {
        synchronized(stateLock) {
            if (registeredContext == null) {
                context.registerComponentCallbacks(this)
                registeredContext = context
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) close()
    }
    override fun onLowMemory() = close()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
