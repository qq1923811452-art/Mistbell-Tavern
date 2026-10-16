package com.mistbell.tavern.v3.feature.embedding

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real Android adapter for LiteRT-LM's EmbeddingEngine.
 *
 * The model bundle must already exist locally. This class never downloads model files or sends
 * embedding input to a network service. Backend status means the LiteRT-LM backend successfully
 * initialized; the SDK does not expose per-operation hardware telemetry, so device-level NPU use
 * still requires a device run and runtime/native logs.
 */
class LiteRtLmEmbeddingGemma2Engine(
    context: Context,
    private val modelFile: File,
    preferredBackends: List<InferenceBackend> = listOf(
        InferenceBackend.NPU,
        InferenceBackend.GPU,
        InferenceBackend.CPU,
    ),
    private val npuModelCompatible: Boolean = false,
    private val diagnostics: DiagnosticLogStore = DiagnosticLogStore(context.applicationContext.filesDir),
) : EmbeddingGemma2InferenceEngine, AutoCloseable {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, "litertlm-embeddinggemma2")
    private val lock = ReentrantLock()
    private val backendAvailability = LiteRtBackendAvailability.detect(
        nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir),
        isArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
        npuModelCompatible = npuModelCompatible,
        npuRuntimeValidated = appContext.hasVerifiedNpuRuntimeManifest(),
    )
    private val backendOrder = preferredBackends
        .filter { it != InferenceBackend.UNKNOWN && backendAvailability.isAvailable(it) }
        .distinct()
    private val skippedBackendReasons = buildList {
        if (InferenceBackend.NPU in preferredBackends && !backendAvailability.isAvailable(InferenceBackend.NPU)) {
            add("NPU skipped: " + backendAvailability.npuReason)
        }
        if (InferenceBackend.GPU in preferredBackends && !backendAvailability.isAvailable(InferenceBackend.GPU)) {
            add("GPU skipped: " + backendAvailability.gpuReason)
        }
    }
    private var engine: EmbeddingEngine? = null
    private var selectedBackend = InferenceBackend.UNKNOWN
    private var fallbackReason: String? = null
    private var lastError: String? = null

    override val runtimeId: String = RUNTIME_ID

    init {
        require(backendOrder.isNotEmpty()) { "No requested backend is available; CPU must remain enabled as a safe fallback." }
        skippedBackendReasons.forEach { logWarn("embedding.backend.skipped.preflight", it) }
        require(modelFile.extension.equals("litertlm", ignoreCase = true)) {
            "EmbeddingGemma 2 model must be a .litertlm bundle."
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        lock.withLock {
            check(engine == null) { "EmbeddingGemma 2 engine is already initialized." }
            try {
                check(modelFile.isFile && modelFile.canRead()) {
                    "EmbeddingGemma 2 model bundle is missing or unreadable."
                }
                check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
                    "LiteRT-LM cache directory could not be created."
                }
            } catch (error: Exception) {
                lastError = "${error::class.java.simpleName}: ${error.message ?: "runtime initialization failed"}"
                    .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                logError("embedding.initialize.preflight.failed", lastError.orEmpty())
                throw error
            }

            val failures = skippedBackendReasons.toMutableList()
            lastError = null
            logInfo(
                "embedding.initialize.started",
                "backendOrder=${backendOrder.joinToString(",")}; nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}",
            )
            for (backend in backendOrder) {
                logInfo("embedding.backend.initialize.started", "backend=${backend.name}")
                val candidate = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend.toLiteRtBackend(),
                        cacheDir = cacheDirectory.absolutePath,
                    ),
                )
                try {
                    candidate.initialize()
                    engine = candidate
                    selectedBackend = backend
                    fallbackReason = failures.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ")
                        ?.take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                    logInfo("embedding.initialize.succeeded", "backend=${backend.name}; fallback=${fallbackReason != null}")
                    return@withLock
                } catch (cancelled: CancellationException) {
                    if (candidate.isInitialized()) runCatching { candidate.close() }
                    throw cancelled
                } catch (error: Exception) {
                    if (candidate.isInitialized()) runCatching { candidate.close() }
                    val reason = "${backend.name}: ${error.message ?: error::class.java.simpleName}".take(180)
                    failures += reason
                    logWarn("embedding.backend.initialize.failed", reason)
                } catch (error: LinkageError) {
                    if (candidate.isInitialized()) runCatching { candidate.close() }
                    val reason = "${backend.name}: native runtime unavailable (${error::class.java.simpleName})"
                    failures += reason
                    logWarn("embedding.backend.initialize.failed", reason)
                }
            }
            selectedBackend = InferenceBackend.UNKNOWN
            lastError = "No configured backend initialized successfully: ${failures.joinToString("; ")}"
                .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
            logError("embedding.initialize.failed", lastError.orEmpty())
            throw IllegalStateException(lastError)
        }
    }

    override suspend fun infer(formattedInputs: List<String>): EmbeddingBatch = withContext(Dispatchers.IO) {
        require(formattedInputs.isNotEmpty()) { "Embedding input batch must not be empty." }
        require(formattedInputs.all(String::isNotBlank)) { "Embedding inputs must not be blank." }
        lock.withLock {
            check(engine != null) { "LiteRT-LM embedding engine is not initialized." }
            val startIndex = backendOrder.indexOf(selectedBackend).coerceAtLeast(0)
            val failures = mutableListOf<String>()
            fallbackReason?.let(failures::add)
            for (index in startIndex until backendOrder.size) {
                val backend = backendOrder[index]
                var candidate = engine.takeIf { selectedBackend == backend }
                if (candidate == null) {
                    runCatching { engine?.close() }.onFailure { logWarn("embedding.engine.close.failed", it::class.java.simpleName) }
                    engine = null
                    try {
                        candidate = EmbeddingEngine(
                            EmbeddingEngineConfig(
                                modelPath = modelFile.absolutePath,
                                backend = backend.toLiteRtBackend(),
                                cacheDir = cacheDirectory.absolutePath,
                            ),
                        )
                        candidate.initialize()
                        engine = candidate
                        selectedBackend = backend
                        logInfo("embedding.backend.fallback.initialized", "backend=${backend.name}")
                    } catch (cancelled: CancellationException) {
                        candidate?.let { runCatching { it.close() } }
                        throw cancelled
                    } catch (error: Exception) {
                        candidate?.let { runCatching { if (it.isInitialized()) it.close() } }
                        val reason = "${backend.name}: ${error.message ?: error::class.java.simpleName}".take(180)
                        failures += reason
                        logWarn("embedding.backend.fallback.initialize.failed", reason)
                        candidate = null
                        continue
                    } catch (error: LinkageError) {
                        candidate?.let { runCatching { if (it.isInitialized()) it.close() } }
                        val reason = "${backend.name}: native runtime unavailable (${error::class.java.simpleName})"
                        failures += reason
                        logWarn("embedding.backend.fallback.initialize.failed", reason)
                        candidate = null
                        continue
                    }
                }
                val active = candidate ?: continue
                val startedAt = System.nanoTime()
                logInfo("embedding.inference.started", "inputCount=${formattedInputs.size}; backend=${backend.name}")
                try {
                    val responses = active.computeEmbeddingBatch(
                        formattedInputs.map { listOf(InputData.Text(it)) },
                        EmbeddingOptions(normalize = true, outputSize = EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS),
                    )
                    val elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                    selectedBackend = backend
                    engine = active
                    fallbackReason = failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
                        ?.take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                    lastError = null
                    logInfo("embedding.inference.succeeded", "outputCount=${responses.size}; dimensions=${responses.firstOrNull()?.embedding?.size ?: 0}; elapsedMs=$elapsedMillis; backend=${backend.name}; fallback=${fallbackReason != null}")
                    return@withLock EmbeddingBatch(
                        vectors = responses.map { response -> response.embedding.toList() },
                        status = InferenceRuntimeStatus(
                            phase = if (fallbackReason == null) InferencePhase.READY else InferencePhase.FALLBACK,
                            modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
                            backend = selectedBackend,
                            runtimeId = runtimeId,
                            backendEvidence = BackendEvidence.CONFIGURED_ONLY,
                            acceleratorName = selectedBackend.name,
                            fallbackReason = fallbackReason,
                            lastExecutionMillis = elapsedMillis,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    logWarn("embedding.inference.cancelled", "backend=${backend.name}")
                    throw cancelled
                } catch (error: Exception) {
                    val reason = "${backend.name}: ${error::class.java.simpleName}: ${error.message ?: "inference failed"}".take(180)
                    failures += reason
                    logError("embedding.inference.failed", reason)
                    runCatching { active.close() }
                    engine = null
                    selectedBackend = InferenceBackend.UNKNOWN
                } catch (error: LinkageError) {
                    val reason = "${backend.name}: native runtime unavailable (${error::class.java.simpleName})"
                    failures += reason
                    logError("embedding.inference.failed", reason)
                    runCatching { active.close() }
                    engine = null
                    selectedBackend = InferenceBackend.UNKNOWN
                }
            }
            lastError = "EmbeddingGemma 2 inference failed on all configured backends: ${failures.joinToString("; ")}"
                .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
            logError("embedding.inference.all_backends_failed", lastError.orEmpty())
            throw IllegalStateException(lastError)
        }
    }

    fun currentStatus(): InferenceRuntimeStatus = lock.withLock {
        if (engine == null) {
            return@withLock if (lastError == null) InferenceRuntimeStatus.idle() else InferenceRuntimeStatus(
                phase = InferencePhase.FAILED,
                modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
                backend = InferenceBackend.UNKNOWN,
                runtimeId = runtimeId,
                lastError = lastError,
            )
        }
        InferenceRuntimeStatus(
            phase = if (lastError != null) InferencePhase.FAILED else if (fallbackReason == null) InferencePhase.READY else InferencePhase.FALLBACK,
            modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
            backend = selectedBackend,
            runtimeId = runtimeId,
            backendEvidence = BackendEvidence.CONFIGURED_ONLY,
            acceleratorName = selectedBackend.name,
            fallbackReason = fallbackReason,
            lastError = lastError,
        )
    }

    override fun close() {
        lock.withLock {
            runCatching { engine?.close() }.onFailure { logWarn("embedding.engine.close.failed", it::class.java.simpleName) }
            engine = null
            selectedBackend = InferenceBackend.UNKNOWN
            fallbackReason = null
            logInfo("embedding.engine.closed")
        }
    }

    private fun logInfo(event: String, detail: String = "") { runCatching { diagnostics.info(event, detail) } }
    private fun logWarn(event: String, detail: String = "") { runCatching { diagnostics.warn(event, detail) } }
    private fun logError(event: String, detail: String = "") { runCatching { diagnostics.error(event, detail) } }

    private fun Context.hasVerifiedNpuRuntimeManifest(): Boolean = runCatching {
        assets.open("native-runtime/qualcomm-sm8650.properties").bufferedReader().useLines { source ->
            val fields = source.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }.toMap()
            fields["status"] == "verified" &&
                fields["target_soc"] == "SM8650" &&
                fields["litertlm_android"] == "0.18.0" &&
                fields["qairt_abi"] == "verified" &&
                fields["dispatch_abi"] == "verified"
        }
    }.getOrDefault(false)

    private fun InferenceBackend.toLiteRtBackend(): Backend = when (this) {
        InferenceBackend.NPU -> Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
        InferenceBackend.GPU -> Backend.GPU()
        InferenceBackend.CPU -> Backend.CPU()
        InferenceBackend.UNKNOWN -> error("UNKNOWN is not a runnable LiteRT-LM backend.")
    }

    companion object {
        const val RUNTIME_ID = "litert-lm-embeddinggemma2-android"
    }
}
