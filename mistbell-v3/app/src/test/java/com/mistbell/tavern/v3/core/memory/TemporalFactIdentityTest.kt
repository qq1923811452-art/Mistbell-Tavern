package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalFactIdentityTest {
    @Test
    fun theSameObservationAlwaysProducesTheSameId() {
        val first = id("scope-a", "turn-1")
        assertEquals(first, id("scope-a", "turn-1"))
        assertTrue(first.startsWith("fact:"))
    }

    @Test
    fun anyContentOrScopeChangeProducesADifferentId() {
        val base = id("scope-a", "turn-1")
        assertNotEquals(base, id("scope-b", "turn-1"))
        assertNotEquals(base, id("scope-a", "turn-2"))
        assertNotEquals(base, TemporalFactIdentity.forObservation("scope-a", "turn-1", "Mira", "mood", "calm"))
    }

    @Test
    fun delimitersInsideFieldsCannotAliasAnotherObservation() {
        assertNotEquals(
            TemporalFactIdentity.forObservation("s", "t", "a|b", "c", "d"),
            TemporalFactIdentity.forObservation("s", "t", "a", "b|c", "d"),
        )
    }

    private fun id(
        scope: String,
        turn: String,
    ) = TemporalFactIdentity.forObservation(scope, turn, "Mira", "location", "harbour")
}
