package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactHistoryReducerTest {
    @Test
    fun conflictingFactSupersedesButPreservesPreviousHistory() {
        val first = fact(id = "f1", value = "blue", timestamp = 100)
        val second = fact(id = "f2", value = "green", timestamp = 200)

        val update = FactHistoryReducer.append(listOf(first), second)

        assertTrue(update.inserted)
        assertEquals(listOf("f1"), update.supersededFactIds)
        assertEquals(2, update.history.size)
        assertEquals(FactStatus.SUPERSEDED, update.history[0].status)
        assertEquals(200L, update.history[0].supersededAtEpochMillis)
        assertEquals("f2", update.history[0].supersededByFactId)
        assertEquals(listOf(second), FactHistoryReducer.activeFacts(update.history))
    }

    @Test
    fun replayingIdenticalFactIsIdempotent() {
        val existing = fact(id = "f1", value = "blue", timestamp = 100)

        val update = FactHistoryReducer.append(listOf(existing), existing)

        assertFalse(update.inserted)
        assertEquals(listOf(existing), update.history)
    }

    @Test
    fun equivalentActiveValueDoesNotCreateDuplicate() {
        val existing = fact(id = "f1", subject = "Mira", predicate = "likes", value = "Tea")
        val duplicate = fact(
            id = "f2",
            subject = " mira ",
            predicate = "LIKES",
            value = " tea ",
            timestamp = 200,
        )

        val update = FactHistoryReducer.append(listOf(existing), duplicate)

        assertFalse(update.inserted)
        assertEquals(listOf(existing), update.history)
    }

    @Test(expected = IllegalArgumentException::class)
    fun reusingFactIdForDifferentContentIsRejected() {
        FactHistoryReducer.append(
            listOf(fact(id = "same", value = "blue")),
            fact(id = "same", value = "green", timestamp = 200),
        )
    }

    private fun fact(
        id: String,
        subject: String = "Mira",
        predicate: String = "eye_color",
        value: String,
        timestamp: Long = 100,
    ) = MemoryFact(
        id = id,
        subject = subject,
        predicate = predicate,
        value = value,
        sourceTurnId = "turn-$id",
        recordedAtEpochMillis = timestamp,
    )
}
