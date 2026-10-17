package com.mistbell.tavern.v3.core.inference

/** Backend selected/reported by the adapter; consult backendEvidence before inferring hardware execution. */
enum class InferenceBackend { CPU, GPU, NPU, UNKNOWN }

enum class InferencePhase { NOT_INITIALIZED, LOADING_MODEL, INITIALIZED, READY, RUNNING, FALLBACK, FAILED }

/** Evidence level for a backend label; a configured delegate is not proof of hardware execution. */
enum class BackendEvidence { NOT_AVAILABLE, CONFIGURED_ONLY, RUNTIME_REPORTED, DEVICE_VERIFIED }

data class RuntimeProbe(
    val runtimeId: String,
    val available: Boolean,
    val reportedBackend: InferenceBackend,
    val detail: String? = null,
) {
    init {
        require(runtimeId.isNotBlank()) { "Runtime ID must not be blank." }
        require(detail == null || detail.length <= MAX_DETAIL_LENGTH) { "Runtime detail is too long." }
    }

    companion object { const val MAX_DETAIL_LENGTH = 500 }
}

data class InferenceRuntimeStatus(
    val phase: InferencePhase,
    val modelId: String?,
    val backend: InferenceBackend,
    val runtimeId: String?,
    val backendEvidence: BackendEvidence = BackendEvidence.NOT_AVAILABLE,
    val acceleratorName: String? = null,
    val fallbackReason: String? = null,
    val lastError: String? = null,
    val lastExecutionMillis: Long? = null,
) {
    init {
        require(modelId == null || modelId.isNotBlank()) { "Model ID must not be blank." }
        require(runtimeId == null || runtimeId.isNotBlank()) { "Runtime ID must not be blank." }
        require(acceleratorName == null || acceleratorName.length <= MAX_LABEL_LENGTH) {
            "Accelerator name is too long."
        }
        require(fallbackReason == null || fallbackReason.length <= MAX_DETAIL_LENGTH) {
            "Fallback reason is too long."
        }
        require(lastError == null || lastError.length <= MAX_DETAIL_LENGTH) { "Error detail is too long." }
        require(lastExecutionMillis == null || lastExecutionMillis >= 0) {
            "Execution duration must not be negative."
        }
        require(phase != InferencePhase.READY || (modelId != null && runtimeId != null)) {
            "Ready status requires a model and runtime."
        }
        require(backend != InferenceBackend.NPU || phase == InferencePhase.INITIALIZED ||
            phase == InferencePhase.READY || phase == InferencePhase.RUNNING || phase == InferencePhase.FALLBACK) {
            "NPU cannot be reported while no runtime is active."
        }
        require(
            backend != InferenceBackend.NPU ||
                backendEvidence == BackendEvidence.RUNTIME_REPORTED ||
                backendEvidence == BackendEvidence.DEVICE_VERIFIED,
        ) {
            "NPU cannot be reported as active without runtime-reported or device-verified execution evidence."
        }
    }

    companion object {
        const val MAX_LABEL_LENGTH = 120
        const val MAX_DETAIL_LENGTH = 500
        fun idle() = InferenceRuntimeStatus(
            phase = InferencePhase.NOT_INITIALIZED,
            modelId = null,
            backend = InferenceBackend.UNKNOWN,
            runtimeId = null,
        )
    }
}

/** Preference order never implies availability; probes must identify their evidence level before claiming hardware execution. */
object InferenceBackendSelection {
    fun select(preferredOrder: List<InferenceBackend>, probes: List<RuntimeProbe>): RuntimeProbe? {
        require(preferredOrder.isNotEmpty()) { "At least one backend preference is required." }
        require(preferredOrder.distinct().size == preferredOrder.size) {
            "Backend preference must not contain duplicates."
        }
        require(probes.map(RuntimeProbe::runtimeId).distinct().size == probes.size) {
            "Runtime IDs must be unique."
        }
        return preferredOrder.asSequence()
            .filter { it != InferenceBackend.UNKNOWN }
            .mapNotNull { backend ->
                probes.firstOrNull {
                    it.available && it.reportedBackend == backend && it.reportedBackend != InferenceBackend.UNKNOWN
                }
            }
            .firstOrNull()
    }

    fun fallbackReason(preferredOrder: List<InferenceBackend>, selected: RuntimeProbe?): String? {
        if (selected == null) {
            return "No available runtime reported a verified CPU, GPU, or NPU backend."
        }
        val firstPreferred = preferredOrder.firstOrNull { it != InferenceBackend.UNKNOWN }
        return if (firstPreferred == selected.reportedBackend) null else {
            "Preferred ${firstPreferred ?: "accelerator"} backend was unavailable; " +
                "using ${selected.reportedBackend} via ${selected.runtimeId}."
        }
    }
}
