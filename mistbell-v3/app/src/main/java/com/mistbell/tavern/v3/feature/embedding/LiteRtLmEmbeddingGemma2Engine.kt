package com.mistbell.tavern.v3.feature.embedding

import android.app.ActivityManager
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
    /** Explicit one-shot diagnostic only; never marks the runtime as verified. */
    private val allowDiagnosticNpuProbe: Boolean = false,
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
        allowDiagnosticNpuProbe = allowDiagnosticNpuProbe,
        apkFiles = buildList {
            add(File(appContext.applicationInfo.sourceDir))
            appContext.applicationInfo.splitSourceDirs.orEmpty().forEach { add(File(it)) }
        },
        androidApiLevel = Build.VERSION.SDK_INT,
    )

    // The Qualcomm SM8650 bundle is an NPU-targeted model. Never try to run it through
    // GPU/CPU just because the NPU runtime is missing: that can fail deep inside tensor
    // allocation and misleadingly looks like a generic memory problem. Portable bundles
    // retain the normal NPU -> GPU -> CPU fallback order.
    private val backendOrder = preferredBackends
        .filter { it != InferenceBackend.UNKNOWN && backendAvailability.isAvailable(it) }
        .filter { !npuModelCompatible || it == InferenceBackend.NPU }
        .distinct()

    private val skippedBackendReasons = buildList {
        if (InferenceBackend.NPU in preferredBackends && !backendAvailability.isAvailable(InferenceBackend.NPU)) {
            add("NPU skipped: " + backendAvailability.npuReason)
        }
        if (npuModelCompatible) {
            add("Qualcomm SM8650 model is NPU-only; GPU/CPU fallback is disabled. Select the portable model for CPU/GPU testing.")
        } else if (InferenceBackend.GPU in preferredBackends && !backendAvailability.isAvailable(InferenceBackend.GPU)) {
            add("GPU skipped: " + backendAvailability.gpuReason)
        }
    }
    private var engine: EmbeddingEngine? = null
    private var selectedBackend = InferenceBackend.UNKNOWN
    private var fallbackReason: String? = null
    private var lastError: String? = null
    private var hasSuccessfulInference = false

    override val runtimeId: String = RUNTIME_ID

    init {
        require(backendOrder.isNotEmpty()) {
            if (npuModelCompatible) {
                "The Qualcomm SM8650 model requires a verified NPU runtime. Install a compatible runtime or select the portable EmbeddingGemma model for CPU/GPU testing."
            } else {
                "No requested embedding backend is available. Check the packaged GPU runtime; CPU fallback is available only for the portable model."
            }
        }
        skippedBackendReasons.forEach { logWarn("embedding.backend.skipped.preflight", it) }
        if (allowDiagnosticNpuProbe && npuModelCompatible) {
            logWarn("embedding.npu.diagnostic_probe.enabled", "manifestVerified=${appContext.hasVerifiedNpuRuntimeManifest()}; productionBackendVerification=unchanged")
        }
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

            // Preflight skips are diagnostic context, not proof that runtime fallback occurred.
            // Only actual backend initialization/inference failures may set runtimeFallbackApplied.
            val failures = mutableListOf<String>()
            lastError = null
            logInfo(
                "embedding.initialize.started",
                "backendOrder=${backendOrder.joinToString(",")}; nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}; runtimeFallbackApplied=false",
            )
            for (backend in backendOrder) {
                logInfo("embedding.backend.initialize.started", "backend=${backend.name}")
                var candidate: EmbeddingEngine? = null
                try {
                    // Keep construction inside the guarded block too: JNI/native loading can fail
                    // before initialize() is reached, and that failure must still be diagnosed
                    // and allow the next configured backend to be tried.
                    val created = EmbeddingEngine(
                        EmbeddingEngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = backend.toLiteRtBackend(),
                            cacheDir = cacheDirectory.absolutePath,
                        ),
                    )
                    candidate = created
                    created.initialize()
                    engine = created
                    selectedBackend = backend
                    hasSuccessfulInference = false
                    fallbackReason = failures.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ")
                        ?.take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                    logInfo("embedding.initialize.succeeded", "backend=${backend.name}; runtimeFallbackApplied=${fallbackReason != null}")
                    return@withLock
                } catch (cancelled: CancellationException) {
                    candidate?.let { runCatching { it.close() } }
                    throw cancelled
                } catch (error: Exception) {
                    candidate?.let { runCatching { it.close() } }
                    val reason = failureDetail(backend, error)
                    failures += reason
                    logWarn("embedding.backend.initialize.failed", reason)
                } catch (error: LinkageError) {
                    candidate?.let { runCatching { it.close() } }
                    val reason = failureDetail(backend, error, fallbackMessage = "native runtime unavailable")
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
                        candidate?.let { runCatching { it.close() } }
                        val reason = failureDetail(backend, error)
                        failures += reason
                        logWarn("embedding.backend.fallback.initialize.failed", reason)
                        candidate = null
                        continue
                    } catch (error: LinkageError) {
                        candidate?.let { runCatching { it.close() } }
                        val reason = "${backend.name}: native runtime unavailable (${error::class.java.simpleName})"
                        failures += reason
                        logWarn("embedding.backend.fallback.initialize.failed", reason)
                        candidate = null
                        continue
                    }
                }
                val active = candidate ?: continue
                val startedAt = System.nanoTime()
                val inputCharLengths = formattedInputs.joinToString(",") { it.length.toString() }
                val inputBatchShape = formattedInputs.joinToString(",") { "1" }
                val options = EmbeddingOptions(
                    normalize = true,
                    outputSize = EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS,
                )
                logInfo(
                    "embedding.inference.started",
                    "inputCount=${formattedInputs.size}; backend=${backend.name}; " +
                        "inputBatchShape=${inputBatchShape}; inputCharLengths=${inputCharLengths}; " +
                        "tokenLengths=unavailable; normalize=true; outputSize=${EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS}",
                )
                if (backend == InferenceBackend.NPU) {
                    val resourceEvidence = runtimeMemoryEvidence().removePrefix("; ")
                    logInfo("embedding.npu.resource_snapshot.before", "backend=NPU; $resourceEvidence")
                }
                try {
                    val responses = active.computeEmbeddingBatch(
                        formattedInputs.map { listOf(InputData.Text(it)) },
                        options,
                    )
                    val elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                    selectedBackend = backend
                    engine = active
                    hasSuccessfulInference = true
                    fallbackReason = failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
                        ?.take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                    lastError = null
                    logInfo(
                        "embedding.inference.succeeded",
                        "outputCount=${responses.size}; dimensions=${responses.firstOrNull()?.embedding?.size ?: 0}; " +
                            "elapsedMs=$elapsedMillis; backend=${backend.name}; runtimeFallbackApplied=${fallbackReason != null}; " +
                            "inputCount=${formattedInputs.size}; inputBatchShape=${inputBatchShape}; " +
                            "inputCharLengths=${inputCharLengths}; tokenLengths=unavailable; " +
                            "backendEvidence=CONFIGURED_ONLY; hardwareExecutionVerified=false",
                    )
                    return@withLock EmbeddingBatch(
                        vectors = responses.map { response -> response.embedding.toList() },
                        status = InferenceRuntimeStatus(
                            phase = if (fallbackReason == null) InferencePhase.READY else InferencePhase.FALLBACK,
                            modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
                            // LiteRT-LM exposes the configured delegate, not proof that the NPU
                            // executed this operation. Do not label NPU as an active backend without evidence.
                            backend = if (selectedBackend == InferenceBackend.NPU) InferenceBackend.UNKNOWN else selectedBackend,
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
                    val reason = failureDetail(backend, error, fallbackMessage = "inference failed")
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

    /**
     * One-shot, diagnostic-only input-length matrix for Qualcomm model failures.
     *
     * Each case gets a fresh NPU engine so a failed native invocation cannot poison the next
     * sample. This deliberately varies text length without inventing token IDs or padding:
     * InputData.Text owns tokenization inside LiteRT-LM. The results can show whether failure
     * correlates with input length, but cannot by themselves prove a tensor-shape root cause.
     */
    suspend fun runNpuInputLengthProbe(): List<String> {
        check(allowDiagnosticNpuProbe && npuModelCompatible) {
            "Input-length matrix is restricted to the one-shot Qualcomm NPU diagnostic probe."
        }
        val lengths = listOf(1, 32, 74, 128, 256, 512, 1024)
        val outcomes = mutableListOf<String>()
        logWarn(
            "embedding.npu.input_length_matrix.started",
            "caseLengths=${lengths.joinToString(",")}; modelProfile=QUALCOMM_SM8650; padding=not_applied; evidenceStatus=pending",
        )
        DiagnosticLogStore.beginNpuMatrix()
        try {
            for (length in lengths) {
            val input = EmbeddingGemma2TextPolicy.formatSearchQuery("x".repeat(length))
            try {
                initialize()
                val result = infer(listOf(input))
                val dimensions = result.vectors.firstOrNull()?.size ?: 0
                val outcome = "chars=$length; result=success; dimensions=$dimensions; elapsedMs=${result.status.lastExecutionMillis ?: -1}"
                outcomes += outcome
                logInfo("embedding.npu.input_length_matrix.case", outcome)
            } catch (cancelled: CancellationException) {
                logWarn("embedding.npu.input_length_matrix.cancelled", "chars=$length")
                throw cancelled
            } catch (error: Exception) {
                val detail = "${error::class.java.simpleName}: ${(error.message ?: "inference failed").take(180)}"
                val outcome = "chars=$length; result=failed; error=$detail"
                outcomes += outcome
                logWarn("embedding.npu.input_length_matrix.case", outcome)
            } finally {
                close()
            }
            }
        } finally {
            DiagnosticLogStore.endNpuMatrix()
        }
        val evidence = NpuEvidenceStore.write(appContext, modelFile, outcomes)
        logWarn(
            "embedding.npu.input_length_matrix.completed",
            "cases=${outcomes.size}; successes=${evidence.successes}; failures=${outcomes.size - evidence.successes}; status=${evidence.status}; evidence=${evidence.file.name}; apkSha256MatchesCurrent=${evidence.apkSha256MatchesCurrent}",
        )
        return outcomes
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
            phase = when {
                lastError != null -> InferencePhase.FAILED
                !hasSuccessfulInference -> InferencePhase.INITIALIZED
                fallbackReason == null -> InferencePhase.READY
                else -> InferencePhase.FALLBACK
            },
            modelId = EmbeddingGemma2TextPolicy.MODEL_ID,
            // Initialization alone is not execution evidence for Qualcomm NPU.
            backend = if (selectedBackend == InferenceBackend.NPU) InferenceBackend.UNKNOWN else selectedBackend,
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
            hasSuccessfulInference = false
            logInfo("embedding.engine.closed")
        }
    }

    /** Keep enough native/JNI context to diagnose model/runtime failures without logging input text. */
    private fun failureDetail(
        backend: InferenceBackend,
        error: Throwable,
        fallbackMessage: String = "runtime failure",
    ): String {
        val message = error.message?.takeIf(String::isNotBlank) ?: fallbackMessage
        val causes = generateSequence(error.cause) { it.cause }
            .take(3)
            .joinToString(" <- ") { "${it::class.java.simpleName}: ${it.message ?: "no message"}" }
            .takeIf(String::isNotBlank)
            ?.let { "; causes=$it" }
            .orEmpty()
        val frame = error.stackTrace.firstOrNull()?.let {
            "; at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }.orEmpty()
        // Keep resource telemetry in its own bounded event. Appending it after a long
        // JNI message and then truncating the combined string silently discarded the very
        // memory evidence needed to diagnose native tensor-allocation failures.
        val memory = runtimeMemoryEvidence()
        if (memory.isNotBlank()) {
            logWarn(
                "embedding.runtime.resource_snapshot",
                "backend=${backend.name}; ${memory.removePrefix("; ")}",
            )
        }
        // The runtime status is intentionally short, but native errors often put the
        // actionable Qualcomm/QNN cause at the END of a long nested LiteRT-LM message.
        // Preserve a separately bounded tail in the exportable diagnostic log so the
        // status-size limit does not erase the root cause (for example, tensor allocation).
        val diagnosticTail = message.takeLast(300)
        val diagnosticCauses = causes.takeLast(100)
        val diagnosticFrame = frame.takeLast(70)
        logError(
            "embedding.runtime.failure_detail",
            "backend=${backend.name}; exception=${error::class.java.simpleName}; " +
                "messageTail=$diagnosticTail$diagnosticCauses$diagnosticFrame",
        )

        val compactMessage = message.take(260)
        val compactCauses = causes.take(150)
        val compactFrame = frame.take(90)
        val prefix = "${backend.name}: ${error::class.java.simpleName}: "
        val suffix = "$compactCauses$compactFrame"
        val messageBudget = (InferenceRuntimeStatus.MAX_DETAIL_LENGTH - prefix.length - suffix.length).coerceAtLeast(40)
        return (prefix + compactMessage.take(messageBudget) + suffix)
            .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
    }

    /**
     * Snapshot resource indicators when native tensor allocation or inference fails.
     * These are diagnostic clues only; they do not prove that low memory caused the failure.
     */
    private fun runtimeMemoryEvidence(): String = runCatching {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val mib = 1024L * 1024L
        val deviceAvailable = memoryInfo.availMem / mib
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / mib
        val heapMax = runtime.maxMemory() / mib
        val modelBytes = modelFile.length().coerceAtLeast(0L)
        val cacheFreeBytes = cacheDirectory.usableSpace.coerceAtLeast(0L)
        "; resources{deviceAvailMiB=$deviceAvailable,lowMemory=${memoryInfo.lowMemory}," +
            "appHeapUsedMiB=$heapUsed,appHeapMaxMiB=$heapMax,modelBytes=$modelBytes,cacheFreeBytes=$cacheFreeBytes}"
    }.getOrDefault("")

    private fun logInfo(event: String, detail: String = "") { runCatching { diagnostics.info(event, detail) } }
    private fun logWarn(event: String, detail: String = "") { runCatching { diagnostics.warn(event, detail) } }
    private fun logError(event: String, detail: String = "") { runCatching { diagnostics.error(event, detail) } }

    private fun Context.hasVerifiedNpuRuntimeManifest(): Boolean =
        NpuEvidenceStore.hasValidEvidence(this, modelFile)

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
