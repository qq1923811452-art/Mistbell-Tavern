package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalFactLedgerTest {
    @Test
    fun changedValueClosesPreviousFactButPreservesHistoryAndProvenance() {
        val first = observation(turn = "turn-1", value = "tea", at = 100L)
        val changed = observation(turn = "turn-2", value = "coffee", at = 200L)

        val afterFirst = TemporalFactLedger.apply(emptyList(), listOf(first))
        val afterChange = TemporalFactLedger.apply(afterFirst, listOf(changed))

        assertEquals(2, afterChange.size)
        val old = afterChange.single { it.value == "tea" }
        val current = afterChange.single { it.value == "coffee" }
        assertEquals("turn-1", old.sourceTurnId)
        assertEquals(200L, old.validUntilEpochMillis)
        assertFalse(old.isCurrent)
        assertEquals("turn-2", current.sourceTurnId)
        assertEquals(200L, current.validFromEpochMillis)
        assertNull(current.validUntilEpochMillis)
        assertTrue(current.isCurrent)
    }

    @Test
    fun replayingSameObservationIsIdempotent() {
        val item = observation(turn = "turn-1", value = "tea", at = 100L)
        val once = TemporalFactLedger.apply(emptyList(), listOf(item))
        val twice = TemporalFactLedger.apply(once, listOf(item))

        assertEquals(once, twice)
    }

    @Test
    fun sameCurrentValueDoesNotCreateDuplicateCurrentFact() {
        val first = observation(turn = "turn-1", subject = "User", predicate = "Likes", value = "Tea", at = 100L)
        val repeated = observation(turn = "turn-2", subject = " user ", predicate = "LIKES", value = "tea", at = 200L)

        val facts = TemporalFactLedger.apply(emptyList(), listOf(first, repeated))

        assertEquals(1, facts.size)
        assertTrue(facts.single().isCurrent)
        assertEquals("turn-1", facts.single().sourceTurnId)
    }

    @Test
    fun factsFromDifferentConversationsDoNotCloseEachOthersHistory() {
        val firstConversation = observation(
            turn = "turn-1",
            conversation = "conversation-1",
            value = "tea",
            at = 100L,
        )
        val secondConversation = observation(
            turn = "turn-2",
            conversation = "conversation-2",
            value = "coffee",
            at = 200L,
        )

        val facts = TemporalFactLedger.apply(
            TemporalFactLedger.apply(emptyList(), listOf(firstConversation)),
            listOf(secondConversation),
        )

        assertEquals(2, facts.size)
        assertTrue(facts.all { it.isCurrent })
        assertEquals(setOf("conversation-1", "conversation-2"), facts.map { it.conversationId }.toSet())
        assertNull(facts.single { it.conversationId == "conversation-1" }.validUntilEpochMillis)
        assertNull(facts.single { it.conversationId == "conversation-2" }.validUntilEpochMillis)
    }

    @Test
    fun sourceTurnContributesToStableIdentity() {
        val first = observation(turn = "turn-1", value = "tea", at = 100L)
        val second = observation(turn = "turn-2", value = "tea", at = 100L)

        assertNotEquals(TemporalFactLedger.stableId(first), TemporalFactLedger.stableId(second))
    }

    private fun observation(
        turn: String,
        conversation: String = "conversation-1",
        subject: String = "user",
        predicate: String = "likes",
        value: String,
        at: Long,
    ) = TemporalFactObservation(
        conversationId = conversation,
        sourceTurnId = turn,
        subject = subject,
        predicate = predicate,
        value = value,
        observedAtEpochMillis = at,
    )
}
