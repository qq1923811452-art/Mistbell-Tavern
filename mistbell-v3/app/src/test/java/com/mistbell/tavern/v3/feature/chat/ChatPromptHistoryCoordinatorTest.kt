package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPromptHistoryCoordinatorTest {
    @Test
    fun defaultBudgetScalesWithConfiguredContextTokens() {
        assertEquals(64_000, defaultHistoryBudgetChars(32_000))
    }

    @Test
    fun defaultBudgetClampsToThePlannerRange() {
        assertEquals(4_000, defaultHistoryBudgetChars(1))
        assertEquals(2_000_000, defaultHistoryBudgetChars(Int.MAX_VALUE))
    }
}
