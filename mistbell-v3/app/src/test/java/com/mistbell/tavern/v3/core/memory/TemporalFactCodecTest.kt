package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TemporalFactCodecTest {
    @Test
    fun roundTripsCurrentAndHistoricalFacts() {
        val facts = listOf(
            TemporalMemoryFact(
                id = "fact-old",
                conversationId = "conversation-1",
                sourceTurnId = "turn-1",
                subject = "user",
                predicate = "likes",
                value = "tea",
                observedAtEpochMillis = 100L,
                validFromEpochMillis = 100L,
                validUntilEpochMillis = 200L,
            ),
            TemporalMemoryFact(
                id = "fact-current",
                conversationId = "conversation-1",
                sourceTurnId = "turn-2",
                subject = "user",
                predicate = "likes",
                value = "coffee",
                observedAtEpochMillis = 200L,
                validFromEpochMillis = 200L,
            ),
        )

        assertEquals(facts, TemporalFactCodec.decode(TemporalFactCodec.encode(facts)))
    }

    @Test
    fun rejectsCorruptedPayload() {
        val bytes = TemporalFactCodec.encode(
            listOf(
                TemporalMemoryFact(
                    id = "fact-1",
                    conversationId = "conversation-1",
                    sourceTurnId = "turn-1",
                    subject = "user",
                    predicate = "likes",
                    value = "tea",
                    observedAtEpochMillis = 100L,
                    validFromEpochMillis = 100L,
                ),
            ),
        )
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            TemporalFactCodec.decode(bytes)
        }
    }

    @Test
    fun rejectsDuplicateFactIds() {
        val fact = TemporalMemoryFact(
            id = "same",
            conversationId = "conversation-1",
            sourceTurnId = "turn-1",
            subject = "user",
            predicate = "likes",
            value = "tea",
            observedAtEpochMillis = 100L,
            validFromEpochMillis = 100L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            TemporalFactCodec.encode(listOf(fact, fact))
        }
    }
}
