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
    fun replayAfterSupersessionDoesNotReactivateOldFact() {
        val original = fact(id = "f1", value = "blue", timestamp = 100)
        val newer = fact(id = "f2", value = "green", timestamp = 200)
        val supersededHistory = FactHistoryReducer.append(listOf(original), newer).history
        val replay = FactHistoryReducer.append(
            supersededHistory,
            original.copy(recordedAtEpochMillis = 999),
        )

        assertFalse(replay.inserted)
        assertEquals(FactStatus.SUPERSEDED, replay.history.first().status)
        assertEquals(supersededHistory, replay.history)
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

    @Test
    fun existingConflictingActiveFactsAreReconciledWithoutLosingHistory() {
        val blue = fact(id = "blue", value = "blue")
        val green = fact(id = "green", value = "green", timestamp = 200)
        val preferred = fact(id = "new-blue", value = " BLUE ", timestamp = 300)

        val update = FactHistoryReducer.append(listOf(blue, green), preferred)

        assertFalse(update.inserted)
        assertEquals(listOf("green"), update.supersededFactIds)
        assertEquals(FactStatus.SUPERSEDED, update.history[1].status)
        assertEquals("blue", update.history[1].supersededByFactId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotAppendAlreadySupersededFact() {
        FactHistoryReducer.append(
            emptyList(),
            fact(id = "old", value = "blue").copy(
                status = FactStatus.SUPERSEDED,
                supersededAtEpochMillis = 200,
                supersededByFactId = "new",
            ),
        )
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
