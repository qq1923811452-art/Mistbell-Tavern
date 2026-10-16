package com.mistbell.tavern.v3.feature.memory

/** Queue state transitions are persisted before work is dispatched. */
class MemoryExtractionJobRepository(private val store: MemoryExtractionJobStore) {
    @Synchronized
    fun enqueue(job: MemoryExtractionJob): Boolean {
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

    @Synchronized
    fun claimDue(now: Long): MemoryExtractionJob? {
        require(now >= 0L)
        val jobs = read()
        val index = jobs.indices.filter { i ->
            val job = jobs[i]
            (job.state == MemoryExtractionJob.State.PENDING ||
                job.state == MemoryExtractionJob.State.RETRY_WAIT) && job.nextAttemptAtEpochMillis <= now
        }.minByOrNull { jobs[it].createdAtEpochMillis } ?: return null
        val job = jobs[index]
        if (job.attemptCount >= MemoryExtractionJob.MAX_ATTEMPTS) {
            store.save(jobs.toMutableList().also { it[index] = job.copy(state = MemoryExtractionJob.State.PERMANENT_FAILURE) })
            return null
        }
        val claimed = job.copy(state = MemoryExtractionJob.State.RUNNING, attemptCount = job.attemptCount + 1)
        store.save(jobs.toMutableList().also { it[index] = claimed })
        return claimed
    }

    @Synchronized
    fun complete(id: String): Boolean = update(id) { job ->
        if (job.state == MemoryExtractionJob.State.RUNNING) job.copy(state = MemoryExtractionJob.State.COMPLETED)
        else job
    }

    @Synchronized
    fun fail(id: String, now: Long, safeError: String, permanent: Boolean = false): Boolean {
        require(now >= 0L)
        // Never persist arbitrary exception text: it can contain request URLs, prompts, or credentials.
        val safe = when (safeError.trim().lowercase()) {
            "network" -> "Network request failed."
            "timeout" -> "Request timed out."
            "rate_limit" -> "Provider rate limit reached."
            "server" -> "Provider server error."
            else -> "Extraction attempt failed."
        }
        return update(id) { job ->
            if (job.state != MemoryExtractionJob.State.RUNNING) job
            else if (permanent || job.attemptCount >= MemoryExtractionJob.MAX_ATTEMPTS) {
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

    @Synchronized
    fun recoverInterrupted(now: Long): Int {
        require(now >= 0L)
        val jobs = read()
        var recovered = 0
        val next = jobs.map { job ->
            if (job.state != MemoryExtractionJob.State.RUNNING) job else {
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

    @Synchronized
    fun list(): List<MemoryExtractionJob> = read()

    private fun update(id: String, transform: (MemoryExtractionJob) -> MemoryExtractionJob): Boolean {
        val jobs = read()
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return false
        val next = transform(jobs[index])
        if (next == jobs[index]) return false
        store.save(jobs.toMutableList().also { it[index] = next })
        return true
    }

    private fun read(): List<MemoryExtractionJob> = when (val result = store.load()) {
        MemoryExtractionJobLoadResult.Missing -> emptyList()
        is MemoryExtractionJobLoadResult.Loaded -> result.jobs
        is MemoryExtractionJobLoadResult.Corrupt -> error(result.safeReason)
    }

    companion object { const val MAX_RETRY_DELAY_MILLIS = 21_600_000L }
}
