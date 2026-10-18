package com.mistbell.tavern.v3.feature.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class MemoryExtractionJobCodecTest {
    private fun job(
        id: String = "job-1",
        state: MemoryExtractionJob.State = MemoryExtractionJob.State.RETRY_WAIT,
    ) = MemoryExtractionJob(
        id = id,
        conversationId = "conversation-1",
        turnIds = listOf("turn-1", "turn-2", "turn-3"),
        providerProfileId = "memory-provider",
        credentialHandle = "memory-key-handle",
        createdAtEpochMillis = 1_000L,
        attemptCount = 2,
        nextAttemptAtEpochMillis = 5_000L,
        state = state,
        lastSafeError = "Temporary network failure.",
    )

    @Test
    fun roundTripsRetryStateAndProducesDeterministicBytes() {
        val jobs = listOf(job("job-b"), job("job-a", MemoryExtractionJob.State.PENDING))
        val encoded = MemoryExtractionJobCodec.encode(jobs)

        assertEquals(jobs.sortedBy { it.id }, MemoryExtractionJobCodec.decode(encoded))
        assertEquals(encoded.toList(), MemoryExtractionJobCodec.encode(jobs.reversed()).toList())
    }

    @Test
    fun decodesExplicitNullCredentialHandleAsNull() {
        val encoded = MemoryExtractionJobCodec.encode(listOf(job()))
        val payload = encoded.copyOfRange(0, encoded.size - 32)
            .toString(Charsets.UTF_8)
            .replace("\\"credential_handle\\":\\"memory-key-handle\\"",
                "\\"credential_handle\\":null")
            .toByteArray(Charsets.UTF_8)
        val withValidChecksum = payload + MessageDigest.getInstance("SHA-256").digest(payload)

        assertEquals(null, MemoryExtractionJobCodec.decode(withValidChecksum).single().credentialHandle)
    }

    @Test
    fun rejectsTamperedQueueBytes() {
        val encoded = MemoryExtractionJobCodec.encode(listOf(job()))
        encoded[encoded.size / 2] = (encoded[encoded.size / 2].toInt() xor 1).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            MemoryExtractionJobCodec.decode(encoded)
        }
    }

    @Test
    fun rejectsDuplicateJobIdsAndInvalidTurnCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryExtractionJobCodec.encode(listOf(job(), job()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            job().copy(turnIds = emptyList())
        }
    }

    @Test
    fun storesOnlyOpaqueCredentialHandleAndNotCredentialValue() {
        val encoded = MemoryExtractionJobCodec.encode(listOf(job()))
        val serialized = encoded.copyOfRange(0, encoded.size - 32).toString(Charsets.UTF_8)

        assertTrue(serialized.contains("memory-key-handle"))
        assertTrue(!serialized.contains("actual-api-secret"))
    }
}
