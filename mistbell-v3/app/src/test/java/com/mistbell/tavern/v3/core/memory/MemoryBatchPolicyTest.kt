package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBatchPolicyTest {
    @Test
    fun waitsForThreeCompletedTurnsByDefault() {
        assertNull(
            MemoryBatchPolicy.nextBatch(
                conversationId = "chat-1",
                orderedTurns = turns(2),
                claimedTurnIds = emptySet(),
            ),
        )
    }

    @Test
    fun schedulesOldestThreeTurnsWithStableId() {
        val turns = turns(5)

        val first = MemoryBatchPolicy.nextBatch("chat-1", turns, emptySet())
        val replay = MemoryBatchPolicy.nextBatch("chat-1", turns, emptySet())

        requireNotNull(first)
        assertEquals(listOf("turn-1", "turn-2", "turn-3"), first.turns.map { it.id })
        assertTrue(first.id.startsWith("memory:"))
        assertEquals(first, replay)
    }

    @Test
    fun skipsTurnsAlreadyClaimedByPendingOrCompletedWork() {
        val plan = MemoryBatchPolicy.nextBatch(
            conversationId = "chat-1",
            orderedTurns = turns(6),
            claimedTurnIds = setOf("turn-1", "turn-2", "turn-3"),
        )

        requireNotNull(plan)
        assertEquals(listOf("turn-4", "turn-5", "turn-6"), plan.turns.map { it.id })
    }

    @Test
    fun planIdChangesWhenMiddleTurnChangesEvenIfFirstAndLastMatch() {
        val allTurns = turns(4)
        val first = MemoryBatchPolicy.nextBatch("chat-1", allTurns, setOf("turn-2"))
        val second = MemoryBatchPolicy.nextBatch("chat-1", allTurns, setOf("turn-3"))

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(listOf("turn-1", "turn-3", "turn-4"), first.turns.map { it.id })
        assertEquals(listOf("turn-1", "turn-2", "turn-4"), second.turns.map { it.id })
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun manualFlushCanSchedulePartialBatch() {
        val plan = MemoryBatchPolicy.nextBatch(
            conversationId = "chat-1",
            orderedTurns = turns(2),
            claimedTurnIds = emptySet(),
            flushPartialBatch = true,
        )

        requireNotNull(plan)
        assertEquals(2, plan.turns.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateTurnIds() {
        MemoryBatchPolicy.nextBatch(
            conversationId = "chat-1",
            orderedTurns = listOf(
                EligibleConversationTurn("duplicate", 1),
                EligibleConversationTurn("duplicate", 2),
            ),
            claimedTurnIds = emptySet(),
        )
    }

    private fun turns(count: Int) = (1L..count.toLong()).map {
        EligibleConversationTurn(id = "turn-$it", ordinal = it)
    }
}
