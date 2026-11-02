package com.mistbell.tavern.v3.feature.memory

/** Queue state transitions are persisted before work is dispatched. */
@Suppress("TooManyFunctions") // Keep queue transitions and claim validation behind the same shared lock.
class MemoryExtractionJobRepository(private val store: MemoryExtractionJobPersistence) {
    fun enqueue(job: MemoryExtractionJob): Boolean =
        synchronized(QUEUE_LOCK) {
            val jobs = read()
            val existing = jobs.firstOrNull { it.id == job.id }
            if (existing != null) {
                require(existing == job) { "Extraction job ID collision." }
                return false
            }
            require(jobs.size < MemoryExtractionJobCodec.MAX_JOBS) { "Extraction queue is full." }
            store.save(jobs + job)
            return true
        }

    fun claimDue(now: Long): MemoryExtractionJob? =
        synchronized(QUEUE_LOCK) {
            require(now >= 0L)
            val jobs = read()
            // Expire every due job whose retry budget is exhausted before selecting work.
            // Returning null immediately after expiring one job used to strand other due jobs
            // until an unrelated future scheduler invocation.
            var changed = false
            val eligible =
                jobs.map { job ->
                    val due =
                        (
                            job.state == MemoryExtractionJob.State.PENDING ||
                                job.state == MemoryExtractionJob.State.RETRY_WAIT
                        ) &&
                            job.nextAttemptAtEpochMillis <= now
                    if (due && job.migrationRoleScopeId == null &&
                        job.attemptCount >= MemoryExtractionJob.MAX_ATTEMPTS
                    ) {
                        changed = true
                        job.copy(
                            state = MemoryExtractionJob.State.PERMANENT_FAILURE,
                            lastSafeError = job.lastSafeError ?: "Retry limit reached.",
                        )
                    } else {
                        job
                    }
                }
            if (changed) store.save(eligible)
            val index =
                eligible.indices.filter { i ->
                    val job = eligible[i]
                    val due =
                        (
                            job.state == MemoryExtractionJob.State.PENDING ||
                                job.state == MemoryExtractionJob.State.RETRY_WAIT
                        ) && job.nextAttemptAtEpochMillis <= now
                    // Persistence sorts jobs by ID, not insertion time. Compare all outstanding jobs
                    // by their chronological key so a later batch cannot overtake an earlier retry.
                    val hasEarlierOutstandingJob =
                        eligible.any { earlier ->
                            blocksLaterJob(earlier, job)
                        }
                    due && job.migrationRoleScopeId == null && !hasEarlierOutstandingJob
                }.minByOrNull { eligible[it].createdAtEpochMillis } ?: return null
            val job = eligible[index]
            val claimed = job.copy(state = MemoryExtractionJob.State.RUNNING, attemptCount = job.attemptCount + 1)
            store.save(eligible.toMutableList().also { it[index] = claimed })
            return claimed
        }

    fun complete(id: String): Boolean =
        synchronized(QUEUE_LOCK) {
            update(id) { job ->
                if (job.state == MemoryExtractionJob.State.RUNNING) {
                    job.copy(state = MemoryExtractionJob.State.COMPLETED)
                } else {
                    job
                }
            }
        }

    fun fail(
        id: String,
        now: Long,
        safeError: String,
        permanent: Boolean = false,
    ): Boolean =
        synchronized(QUEUE_LOCK) {
            require(now >= 0L)
            // Never persist arbitrary exception text: it can contain request URLs, prompts, or credentials.
            val safe =
                when (safeError.trim().lowercase()) {
                    "network" -> "Network request failed."
                    "timeout" -> "Request timed out."
                    "rate_limit" -> "Provider rate limit reached."
                    "server" -> "Provider server error."
                    "response" -> "Provider response failed validation."
                    "client" -> "Provider rejected the request."
                    "source_unavailable" -> "Source chat history is temporarily unavailable."
                    else -> "Extraction attempt failed."
                }
            return update(id) { job ->
                if (job.state != MemoryExtractionJob.State.RUNNING) {
                    job
                } else if (permanent || job.attemptCount >= MemoryExtractionJob.MAX_ATTEMPTS) {
                    job.copy(state = MemoryExtractionJob.State.PERMANENT_FAILURE, lastSafeError = safe)
                } else {
                    val exponent = (job.attemptCount - 1).coerceIn(0, 10)
                    val delay = (30_000L shl exponent).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                    job.copy(
                        state = MemoryExtractionJob.State.RETRY_WAIT,
                        nextAttemptAtEpochMillis = if (Long.MAX_VALUE - now < delay) Long.MAX_VALUE else now + delay,
                        lastSafeError = safe,
                    )
                }
            }
        }

    fun recoverInterrupted(now: Long): Int =
        synchronized(QUEUE_LOCK) {
            require(now >= 0L)
            val jobs = read()
            var recovered = 0
            val next =
                jobs.map { job ->
                    if (job.state != MemoryExtractionJob.State.RUNNING || job.migrationRoleScopeId != null) {
                        job
                    } else {
                        recovered++
                        job.copy(
                            state = MemoryExtractionJob.State.RETRY_WAIT,
                            nextAttemptAtEpochMillis = now,
                            lastSafeError = "Previous attempt was interrupted.",
                        )
                    }
                }
            if (recovered > 0) store.save(next)
            return recovered
        }

    /** Re-arms permanently failed batches after the user fixes configuration or installs a fix. */
    fun retryPermanentFailures(
        conversationId: String,
        now: Long,
        credentialHandle: String,
    ): Int =
        synchronized(QUEUE_LOCK) {
            require(credentialHandle.isNotBlank() && credentialHandle.length <= MAX_CREDENTIAL_HANDLE_CHARS)
            require(conversationId.isNotBlank())
            require(now >= 0L)
            val jobs = read()
            var retried = 0
            val next =
                jobs.map { job ->
                    if (job.conversationId == conversationId &&
                        job.state == MemoryExtractionJob.State.PERMANENT_FAILURE
                    ) {
                        retried++
                        job.copy(
                            state = MemoryExtractionJob.State.RETRY_WAIT,
                            attemptCount = 0,
                            credentialHandle = credentialHandle,
                            nextAttemptAtEpochMillis = now,
                            lastSafeError = null,
                        )
                    } else {
                        job
                    }
                }
            if (retried > 0) store.save(next)
            return retried
        }

    /**
     * Copies memory and rebinds only the selected conversation as one queue/commit critical section.
     * The callback must return true only after a durable, retry-safe migration succeeds (including
     * the existing-role/no-copy case). Failure leaves a durable fence; retry this same migration
     * before allowing further work. The fence also covers a crash between copy and queue save.
     * Call on IO before exposing role memory or scheduling work for this conversation.
     */
    fun migrateScope(
        legacyMemoryScopeId: String,
        roleMemoryScopeId: String,
        historyScopeId: String,
        migrate: () -> Boolean,
    ): Int = synchronized(QUEUE_LOCK) {
        require(legacyMemoryScopeId.isNotBlank() && roleMemoryScopeId.isNotBlank() && historyScopeId.isNotBlank())
        if (legacyMemoryScopeId == roleMemoryScopeId) return 0
        val jobs = read()
        // Validate before the copy can change any store.
        jobs.filter { it.conversationId == legacyMemoryScopeId }.forEach { job ->
            require(job.historyScopeId == legacyMemoryScopeId || job.historyScopeId == historyScopeId) {
                "Legacy extraction job belongs to a different history scope."
            }
        }
        // Persist a fence BEFORE touching memory files. If copy or final queue persistence fails,
        // a restarted worker must not resume writing the old store. The same migration can retry.
        val fenced = jobs.map { job ->
            if (job.conversationId == legacyMemoryScopeId && legacyMemoryScopeId != roleMemoryScopeId) {
                require(job.migrationRoleScopeId == null || job.migrationRoleScopeId == roleMemoryScopeId)
                job.copy(migrationRoleScopeId = roleMemoryScopeId)
            } else job
        }
        if (fenced != jobs) store.save(fenced)
        check(migrate()) { "Memory scope migration did not complete." }
        var rebound = 0
        val next = fenced.map { job ->
            if (job.conversationId != legacyMemoryScopeId || legacyMemoryScopeId == roleMemoryScopeId) {
                job
            } else {
                require(job.historyScopeId == legacyMemoryScopeId || job.historyScopeId == historyScopeId) {
                    "Legacy extraction job belongs to a different history scope."
                }
                rebound++
                rebindMigratedJob(job, legacyMemoryScopeId, roleMemoryScopeId, historyScopeId)
            }
        }
        if (rebound > 0) store.save(next)
        rebound
    }

    /** Held through fact/text writes AND their completion callback, against scope migration. */
    fun commitIfCurrent(claim: MemoryExtractionJob, commit: () -> Unit): Boolean = synchronized(QUEUE_LOCK) {
        if (claim.state != MemoryExtractionJob.State.RUNNING || claim.migrationRoleScopeId != null ||
            read().none { it == claim }) return false
        commit()
        true
    }

    fun complete(claim: MemoryExtractionJob): Boolean = synchronized(QUEUE_LOCK) {
        if (read().none { it == claim }) false else complete(claim.id)
    }

    fun fail(
        claim: MemoryExtractionJob,
        now: Long,
        safeError: String,
        permanent: Boolean = false,
    ): Boolean = synchronized(QUEUE_LOCK) {
        if (read().none { it == claim }) false else fail(claim.id, now, safeError, permanent)
    }

    /** Includes completed batches even when the preference cursor was lost or predates migration. */
    fun coveredTurnIds(historyScopeId: String): Set<String> = synchronized(QUEUE_LOCK) {
        read().filter { it.historyScopeId == historyScopeId && it.state != MemoryExtractionJob.State.PERMANENT_FAILURE }
            .flatMap { it.turnIds }.toSet()
    }

    fun list(): List<MemoryExtractionJob> = synchronized(QUEUE_LOCK) { read() }

    private fun blocksLaterJob(
        earlier: MemoryExtractionJob,
        job: MemoryExtractionJob,
    ): Boolean =
        earlier.id != job.id &&
            earlier.conversationId == job.conversationId &&
            (
                earlier.createdAtEpochMillis < job.createdAtEpochMillis ||
                    (earlier.createdAtEpochMillis == job.createdAtEpochMillis && earlier.id < job.id)
            ) &&
            (
                earlier.state == MemoryExtractionJob.State.PENDING ||
                    earlier.state == MemoryExtractionJob.State.RUNNING ||
                    earlier.state == MemoryExtractionJob.State.RETRY_WAIT
            )

    private fun update(
        id: String,
        transform: (MemoryExtractionJob) -> MemoryExtractionJob,
    ): Boolean {
        val jobs = read()
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return false
        val next = transform(jobs[index])
        return if (next == jobs[index]) {
            false
        } else {
            store.save(jobs.toMutableList().also { it[index] = next })
            true
        }
    }

    private fun read(): List<MemoryExtractionJob> =
        when (val result = store.load()) {
            MemoryExtractionJobLoadResult.Missing -> emptyList()
            is MemoryExtractionJobLoadResult.Loaded -> result.jobs
            is MemoryExtractionJobLoadResult.Corrupt -> error(result.safeReason)
        }

    companion object {
        const val MAX_RETRY_DELAY_MILLIS = 21_600_000L
        private const val MAX_CREDENTIAL_HANDLE_CHARS = 256

        // Worker, scheduler and UI each create repositories for the same queue file.
        private val QUEUE_LOCK = Any()
    }
}

private fun rebindMigratedJob(
    job: MemoryExtractionJob,
    legacyMemoryScopeId: String,
    roleMemoryScopeId: String,
    historyScopeId: String,
): MemoryExtractionJob =
    job.copy(
        conversationId = roleMemoryScopeId,
        historyScopeId = historyScopeId,
        legacyHistoryScopeId = job.legacyHistoryScopeId ?: legacyMemoryScopeId,
        migrationRoleScopeId = null,
        attemptCount = if (job.state == MemoryExtractionJob.State.RUNNING) {
            (job.attemptCount - 1).coerceAtLeast(0)
        } else job.attemptCount,
        // An in-flight request has stores and input bound to the old scope. Revoke it;
        // its late response cannot commit or change the rebound job's retry state.
        state = if (job.state == MemoryExtractionJob.State.RUNNING) {
            MemoryExtractionJob.State.RETRY_WAIT
        } else job.state,
    )
