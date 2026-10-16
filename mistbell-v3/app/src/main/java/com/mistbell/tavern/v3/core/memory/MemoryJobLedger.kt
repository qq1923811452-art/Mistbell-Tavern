package com.mistbell.tavern.v3.core.memory

/**
 * Durable job state for memory extraction. Persist each transition before dispatching work so
 * process death cannot silently lose a selected batch. A retry reuses the same stable plan ID.
 */
enum class MemoryJobStatus { PENDING, PROCESSING, RETTRY_WAIT, COMPLETED, PERMANENT_FAILURE }

data class MemoryExtractionJob(
    val plan: MemoryBatchPlan,
    val status: MemoryJobStatus = MemoryJobStatus.PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMillis: Long = 0,
    val lastSafeError: String? = null,
    val completedAtEpochMillis: Long? = null,
) {
    init {
        require(attemptCount >= 0) { "Attempt count must not be negative." }
        require(nextAttemptAtEpochMillis >= 0) { "Retry timestamp must not be negative." }
        require(lastSafeError == null || lastSafeError.length <= MAX_ERROR_LENGTH) {
            "Safe error detail is too long."
        }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= 0) {
            "Completion timestamp must not be negative."
        }
        require(
            (status == MemoryJobStatus.COMPLETED) == (completedAtEpochMillis != null),
        ) { "Completion timestamp must match completed status." }
        require(status == MemoryJobStatus.RETTRY_WAIT || nextAttemptAtEpochMillis == 0L) {
            "Only retry-wait jobs may have a scheduled retry time."
        }
    }

    companion object { const val MAX_ERROR_LENGTH = 500 }
}

data class MemoryJobLedger(val jobs: List<MemoryExtractionJob> = emptyList()) {
    init {
        require(jobs.map { it.plan.id }.distinct().size == jobs.size) {
            "Memory job IDs must be unique."
        }
        val claimedTurnIds = jobs
            .filter { it.status != MemoryJobStatus.PERMANENT_FAILURE }
            .flatMap { job -> job.plan.turns.map(EligibleConversationTurn::id) }
        require(claimedTurnIds.distinct().size == claimedTurnIds.size) {
            "A conversation turn cannot belong to multiple active or completed jobs."
        }
    }

    fun enqueue(plan: MemoryBatchPlan): MemoryJobLedger {
        val existing = jobs.firstOrNull { it.plan.id == plan.id }
        if (existing != null) {
            require(existing.plan == plan) { "A memory job ID cannot be reused for a different plan." }
            return this
        }
        val occupied = jobs
            .filter { it.status != MemoryJobStatus.PERMANENT_FAILURE }
            .flatMap { it.plan.turns.map(EligibleConversationTurn::id) }
            .toSet()
        require(plan.turns.none { it.id in occupied }) {
            "One or more turns are already claimed by another memory job."
        }
        return copy(jobs = jobs + MemoryExtractionJob(plan))
    }

    /** Marks the oldest eligible job as processing and increments its attempt count. */
    fun claimNext(nowEpochMillis: Long): Pair<MemoryJobLedger, MemoryExtractionJob?> {
        require(nowEpochMillis >= 0)
        val candidate = jobs.firstOrNull {
            it.status == MemoryJobStatus.PENDING ||
                (it.status == MemoryJobStatus.RETTRY_WAIT && it.nextAttemptAtEpochMillis <= nowEpochMillis)
        } ?: return this to null
        val claimed = candidate.copy(
            status = MemoryJobStatus.PROCESSING,
            attemptCount = Math.addExact(candidate.attemptCount, 1),
            nextAttemptAtEpochMillis = 0,
            lastSafeError = null,
        )
        return copy(jobs = jobs.map { if (it.plan.id == candidate.plan.id) claimed else it }) to claimed
    }

    fun complete(planId: String, nowEpochMillis: Long): MemoryJobLedger {
        require(nowEpochMillis >= 0)
        val job = requireJob(planId)
        require(job.status == MemoryJobStatus.PROCESSING) { "Only a processing job can complete." }
        val completed = job.copy(
            status = MemoryJobStatus.COMPLETED,
            nextAttemptAtEpochMillis = 0,
            lastSafeError = null,
            completedAtEpochMillis = nowEpochMillis,
        )
        return replace(completed)
    }

    fun retry(
        planId: String,
        nowEpochMillis: Long,
        delayMillis: Long,
        safeError: String,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): MemoryJobLedger {
        require(nowEpochMillis >= 0)
        require(delayMillis >= 0)
        require(maxAttempts > 0)
        require(safeError.isNotBlank() && safeError.length <= MemoryExtractionJob.MAX_ERROR_LENGTH)
        val job = requireJob(planId)
        require(job.status == MemoryJobStatus.PROCESSING) { "Only a processing job can retry." }
        val next = if (job.attemptCount >= maxAttempts) {
            job.copy(
                status = MemoryJobStatus.PERMANENT_FAILURE,
                nextAttemptAtEpochMillis = 0,
                lastSafeError = safeError,
            )
        } else {
            job.copy(
                status = MemoryJobStatus.RETTRY_WAIT,
                nextAttemptAtEpochMillis = Math.addExact(nowEpochMillis, delayMillis),
                lastSafeError = safeError,
            )
        }
        return replace(next)
    }

    fun recoverInterruptedProcessing(safeError: String = DEFAULT_INTERRUPTED_ERROR): MemoryJobLedger {
        require(safeError.isNotBlank() && safeError.length <= MemoryExtractionJob.MAX_ERROR_LENGTH)
        return copy(
            jobs = jobs.map { job ->
                if (job.status == MemoryJobStatus.PROCESSING) {
                    job.copy(
                        status = MemoryJobStatus.RETTRY_WAIT,
                        nextAttemptAtEpochMillis = 0,
                        lastSafeError = safeError,
                    )
                } else {
                    job
                }
            },
        )
    }

    private fun requireJob(planId: String): MemoryExtractionJob =
        jobs.firstOrNull { it.plan.id == planId }
            ?: throw IllegalArgumentException("Unknown memory job ID.")

    private fun replace(replacement: MemoryExtractionJob) =
        copy(jobs = jobs.map { if (it.plan.id == replacement.plan.id) replacement else it })

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_INTERRUPTED_ERROR = "Previous extraction was interrupted."
    }
}
