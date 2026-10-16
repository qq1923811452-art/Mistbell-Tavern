package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryJobLedgerCodecTest {
    private fun plan() = MemoryBatchPlan(
        id = "batch-1",
        conversationId = "conversation-1",
        turns = listOf(
            EligibleConversationTurn("turn-1", 1),
            EligibleConversationTurn("turn-2", 2),
            EligibleConversationTurn("turn-3", 3),
        ),
    )

    @Test
    fun roundTripsPendingAndRetryingJobs() {
        val pending = MemoryJobLedger().enqueue(plan())
        val (processing, _) = pending.claimNext(nowEpochMillis = 100)
        val retrying = processing.retry(
            planId = "batch-1",
            nowEpochMillis = 100,
            delayMillis = 250,
            safeError = "Temporary provider failure.",
        )
        assertEquals(retrying, MemoryJobLedgerCodec.decode(MemoryJobLedgerCodec.encode(retrying)))
    }

    @Test
    fun roundTripsCompletedJobs() {
        val (processing, _) = MemoryJobLedger().enqueue(plan()).claimNext(nowEpochMillis = 10)
        val complete = processing.complete("batch-1", nowEpochMillis = 20)
        assertEquals(complete, MemoryJobLedgerCodec.decode(MemoryJobLedgerCodec.encode(complete)))
    }

    @Test
    fun rejectsChecksumMismatch() {
        val encoded = MemoryJobLedgerCodec.encode(MemoryJobLedger().enqueue(plan()))
        encoded[encoded.lastIndex - 1] = (encoded[encoded.lastIndex - 1].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            MemoryJobLedgerCodec.decode(encoded)
        }
    }

    @Test
    fun rejectsTruncatedInput() {
        val encoded = MemoryJobLedgerCodec.encode(MemoryJobLedger().enqueue(plan()))
        assertThrows(IllegalArgumentException::class.java) {
            MemoryJobLedgerCodec.decode(encoded.copyOf(encoded.size - 3))
        }
    }
}
