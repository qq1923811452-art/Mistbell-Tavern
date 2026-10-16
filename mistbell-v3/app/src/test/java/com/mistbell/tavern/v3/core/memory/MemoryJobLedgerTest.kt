package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryJobLedgerTest {
    private fun plan(id: String = "batch-1", start: Int = 1) = MemoryBatchPlan(
        id = id,
        conversationId = "conversation-1",
        turns = (start until start + 3).map { EligibleConversationTurn("turn-$it", it.toLong()) },
    )

    @Test
    fun enqueueIsIdempotentForTheSameStablePlan() {
        val plan = plan()
        val ledger = MemoryJobLedger().enqueue(plan)
        assertEquals(ledger, ledger.enqueue(plan))
        assertEquals(1, ledger.jobs.size)
    }

    @Test
    fun rejectsDifferentPlansWithSameId() {
        val ledger = MemoryJobLedger().enqueue(plan())
        assertThrows(IllegalArgumentException::class.java) {
            ledger.enqueue(plan(start = 10))
        }
    }

    @Test
    fun claimsInOrderAndCompletesExactlyOnce() {
        val ledger = MemoryJobLedger().enqueue(plan())
        val (processing, claimed) = ledger.claimNext(nowEpochMillis = 100)
        assertEquals(MemoryJobStatus.PROCESSING, claimed?.status)
        assertEquals(1, claimed?.attemptCount)
        val complete = processing.complete("batch-1", nowEpochMillis = 200)
        assertEquals(MemoryJobStatus.COMPLETED, complete.jobs.single().status)
        assertEquals(200L, complete.jobs.single().completedAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            complete.complete("batch-1", nowEpochMillis = 300)
        }
    }

    @Test
    fun retryIsNotClaimedBeforeScheduledTime() {
        val pending = MemoryJobLedger().enqueue(plan())
        val (processing, _) = pending.claimNext(nowEpochMillis = 100)
        val retry = processing.retry(
            planId = "batch-1",
            nowEpochMillis = 100,
            delayMillis = 500,
            safeError = "Temporary provider error.",
        )
        val (tooEarly, earlyClaim) = retry.claimNext(nowEpochMillis = 599)
        assertNull(earlyClaim)
        assertEquals(retry, tooEarly)
        val (claimed, job) = retry.claimNext(nowEpochMillis = 600)
        assertEquals(MemoryJobStatus.PROCESSING, job?.status)
        assertEquals(2, job?.attemptCount)
        assertEquals(MemoryJobStatus.PROCESSING, claimed.jobs.single().status)
    }

    @Test
    fun reachingAttemptLimitMovesJobToPermanentFailure() {
        var ledger = MemoryJobLedger().enqueue(plan())
        repeat(MemoryJobLedger.DEFAULT_MAX_ATTEMPTS) {
            val (processing, job) = ledger.claimNext(nowEpochMillis = 1000L + it)
            requireNotNull(job)
            ledger = processing.retry(
                planId = "batch-1",
                nowEpochMillis = 1000L + it,
                delayMillis = 0,
                safeError = "Provider unavailable.",
            )
        }
        assertEquals(MemoryJobStatus.PERMANENT_FAILURE, ledger.jobs.single().status)
    }

    @Test
    fun processDeathRecoveryMakesProcessingJobRetryable() {
        val pending = MemoryJobLedger().enqueue(plan())
        val (processing, _) = pending.claimNext(nowEpochMillis = 1)
        val recovered = processing.recoverInterruptedProcessing()
        assertEquals(MemoryJobStatus.RETRY_WAIT, recovered.jobs.single().status)
        assertEquals(0L, recovered.jobs.single().nextAttemptAtEpochMillis)
        assertEquals(MemoryJobLedger.DEFAULT_INTERRUPTED_ERROR, recovered.jobs.single().lastSafeError)
    }

    @Test
    fun repeatedProcessDeathEventuallyStopsRetrying() {
        var ledger = MemoryJobLedger().enqueue(plan())
        repeat(MemoryJobLedger.DEFAULT_MAX_ATTEMPTS) { attempt ->
            val (processing, claimed) = ledger.claimNext(nowEpochMillis = attempt.toLong())
            requireNotNull(claimed)
            ledger = processing.recoverInterruptedProcessing()
        }
        assertEquals(MemoryJobStatus.PERMANENT_FAILURE, ledger.jobs.single().status)
        assertEquals(MemoryJobLedger.DEFAULT_MAX_ATTEMPTS, ledger.jobs.single().attemptCount)
    }

    @Test
    fun cannotClaimWhenNoEligibleJobsExist() {
        val (ledger, claimed) = MemoryJobLedger().claimNext(nowEpochMillis = 0)
        assertEquals(MemoryJobLedger(), ledger)
        assertNull(claimed)
    }
}
