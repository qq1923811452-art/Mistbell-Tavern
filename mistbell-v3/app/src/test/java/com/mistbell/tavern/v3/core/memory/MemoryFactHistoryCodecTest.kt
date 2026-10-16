package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryFactHistoryCodecTest {
    @Test
    fun roundTripsActiveAndSupersededFacts() {
        val history = listOf(
            MemoryFact(
                id = "fact-old",
                subject = "user",
                predicate = "drink",
                value = "coffee",
                sourceTurnId = "turn-1",
                recordedAtEpochMillis = 100,
                status = FactStatus.SUPERSEDED,
                supersededAtEpochMillis = 200,
                supersededByFactId = "fact-new",
            ),
            MemoryFact(
                id = "fact-new",
                subject = "user",
                predicate = "drink",
                value = "tea",
                sourceTurnId = "turn-2",
                recordedAtEpochMillis = 200,
            ),
        )
        assertEquals(history, MemoryFactHistoryCodec.decode(MemoryFactHistoryCodec.encode(history)))
    }

    @Test
    fun rejectsChecksumMismatch() {
        val encoded = MemoryFactHistoryCodec.encode(
            listOf(
                MemoryFact(
                    id = "fact-1",
                    subject = "user",
                    predicate = "likes",
                    value = "tea",
                    sourceTurnId = "turn-1",
                    recordedAtEpochMillis = 1,
                ),
            ),
        )
        encoded[encoded.lastIndex - 1] = (encoded[encoded.lastIndex - 1].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            MemoryFactHistoryCodec.decode(encoded)
        }
    }

    @Test
    fun rejectsTruncatedPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryFactHistoryCodec.decode(byteArrayOf(1, 2, 3, 4))
        }
    }
}
