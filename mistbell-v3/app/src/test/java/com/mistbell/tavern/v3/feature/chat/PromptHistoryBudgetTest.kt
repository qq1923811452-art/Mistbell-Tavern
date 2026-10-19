package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptHistoryBudgetTest {
    @Test fun keepsNewestWholeMessagesWithinBothLimits() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "old"),
            PromptMessage(PromptRole.ASSISTANT, "12345"),
            PromptMessage(PromptRole.USER, "new"),
        )
        assertEquals(listOf("old", "new"), PromptHistoryBudget.select(history, 6, 2).map { it.content })
    }

    @Test fun zeroBudgetReturnsNoHistory() {
        assertEquals(emptyList<PromptMessage>(), PromptHistoryBudget.select(listOf(PromptMessage(PromptRole.USER, "x")), 0, 5))
    }
}
