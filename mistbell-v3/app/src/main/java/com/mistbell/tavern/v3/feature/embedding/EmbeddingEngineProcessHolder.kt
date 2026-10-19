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

    enum class CloseReason { MODEL_CHANGED, MEMORY_PRESSURE, EXPLICIT }

    private val mutex = Mutex()
    private val stateLock = Any()
    @Volatile private var current: Entry? = null
    private var lastRequestedKey: EngineKey? = null
    @Volatile private var registeredContext: Context? = null
    private var memoryGeneration = 0L

    suspend fun acquire(
        context: Context,
        modelFile: File,
        npuModelCompatible: Boolean,
        diagnostics: DiagnosticLogStore = DiagnosticLogStore(context.applicationContext.filesDir),
    ): LiteRtLmEmbeddingGemma2Engine {
        val appContext = context.applicationContext
        EmbeddingVectorCache.setLogger { event, detail -> runCatching { diagnostics.info(event, detail) } }
        registerCallbacks(appContext)
        val key = EngineKey(modelFile.canonicalPath, modelFile.length(), modelFile.lastModified(), npuModelCompatible)
        mutex.lock()
        try {
            val modelChanged = synchronized(stateLock) {
                current?.takeIf { it.key == key }?.let { return it.engine }
                val changed = lastRequestedKey != null && lastRequestedKey != key
                lastRequestedKey = key
                changed
            }
            closeCurrent(clearCache = modelChanged)
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

    fun close(reason: CloseReason = CloseReason.EXPLICIT) {
        synchronized(stateLock) {
            memoryGeneration++
            if (reason == CloseReason.MODEL_CHANGED) lastRequestedKey = null
        }
        closeCurrent(clearCache = reason == CloseReason.MODEL_CHANGED)
    }

    private fun closeCurrent(clearCache: Boolean = false) {
        val old = synchronized(stateLock) { current.also { current = null } }
        old?.let { runCatching { it.engine.close() } }
        if (clearCache) EmbeddingVectorCache.clear()
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
        // RUNNING_LOW and UI_HIDDEN can occur during ordinary background transitions.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) close(CloseReason.MEMORY_PRESSURE)
    }
    override fun onLowMemory() = close(CloseReason.MEMORY_PRESSURE)
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
