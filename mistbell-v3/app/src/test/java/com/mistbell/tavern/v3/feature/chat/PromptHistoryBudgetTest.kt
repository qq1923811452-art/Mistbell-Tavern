package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptHistoryBudgetTest {
    @Test
    fun keepsNewestWholeMessagesWithinBothLimits() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "old"),
            PromptMessage(PromptRole.ASSISTANT, "12345"),
            PromptMessage(PromptRole.USER, "new"),
        )
        assertEquals(listOf("new"), PromptHistoryBudget.select(history, 6, 2).map { it.content })
    }

    @Test
    fun oversizedNewestMessageIsPreservedWholeInsteadOfTruncated() {
        val oversizedReply = "角色回复很长".repeat(2_000)
        val history = listOf(PromptMessage(PromptRole.USER, "old"), PromptMessage(PromptRole.ASSISTANT, oversizedReply))
        assertEquals(listOf(PromptMessage(PromptRole.ASSISTANT, oversizedReply)), PromptHistoryBudget.select(history, 5, 2))
    }

    @Test
    fun oversizedMessageBreaksTheNewestContiguousHistoryWindow() {
        val history = listOf(
            PromptMessage(PromptRole.USER, "older"),
            PromptMessage(PromptRole.ASSISTANT, "too long"),
            PromptMessage(PromptRole.USER, "new"),
        )
        assertEquals(listOf("new"), PromptHistoryBudget.select(history, 4, 3).map { it.content })
    }

    @Test
    fun zeroBudgetReturnsNoHistory() {
        assertEquals(emptyList<PromptMessage>(), PromptHistoryBudget.select(listOf(PromptMessage(PromptRole.USER, "x")), 0, 5))
    }

    @Test
    fun alignsTrimmedWindowStartDownToTenMessageBlock() {
        val history = numberedHistory(30)
        // The newest 12 messages fit the character budget, leaving an unaligned start at index 18.
        val selected = PromptHistoryBudget.select(history, maxCharacters = 36, maxMessages = 30)
        assertEquals(20, selected.size)
        assertEquals("m10", selected.first().content)
        assertEquals("m29", selected.last().content)
        assertEquals(history.subList(10, 30), selected)
    }

    @Test
    fun blockAlignmentAddsAtMostNineMessagesBeyondTheCharacterBudget() {
        val history = numberedHistory(30)
        // The newest 11 messages fit (33 characters); aligning the start from 19 to 10 adds 9 more messages.
        val selected = PromptHistoryBudget.select(history, maxCharacters = 33, maxMessages = 30)
        assertEquals(20, selected.size)
        assertEquals(9, selected.size - 11)
        assertEquals(history.subList(19, 30), selected.takeLast(11))
        assertTrue(selected.sumOf { it.content.length } > 33)
    }

    @Test
    fun alreadyAlignedWindowIsNotExpanded() {
        val history = numberedHistory(30)
        // Exactly 10 newest messages fit, so the resulting start index 20 is already block-aligned.
        val selected = PromptHistoryBudget.select(history, maxCharacters = 30, maxMessages = 30)
        assertEquals(history.subList(20, 30), selected)
        assertEquals(30, selected.sumOf { it.content.length })
    }

    @Test
    fun shortHistoryKeepsTheExactNewestWindow() {
        val history = (0 until 9).map { index ->
            PromptMessage(
                if (index % 2 == 0) PromptRole.USER else PromptRole.ASSISTANT,
                "s$index",
            )
        }
        // Three newest messages fit; a history shorter than one block is not expanded backwards.
        val selected = PromptHistoryBudget.select(history, maxCharacters = 6, maxMessages = 9)
        assertEquals(listOf("s6", "s7", "s8"), selected.map { it.content })
    }

    @Test
    fun doesNotAlignBackwardsWhenFewerThanOneBlockIsExcluded() {
        val history = numberedHistory(25)
        // 20 newest messages fit, leaving only 5 excluded; expanding to block 0 would return all 25 messages.
        val selected = PromptHistoryBudget.select(history, maxCharacters = 60, maxMessages = 25)
        assertEquals(history.subList(5, 25), selected)
    }

    @Test
    fun oversizedNewestMessageIsRetainedWhenTheWindowIsBlockAligned() {
        val oversizedReply = "回".repeat(200)
        val history = numberedHistory(24) + PromptMessage(PromptRole.USER, oversizedReply)
        // The newest message alone exceeds the budget, but it is kept whole; the window still snaps to index 20.
        val selected = PromptHistoryBudget.select(history, maxCharacters = 10, maxMessages = 25)
        assertEquals(history.subList(20, 25), selected)
        assertEquals(oversizedReply, selected.last().content)
        assertTrue(selected.sumOf { it.content.length } > 10)
    }

    private fun numberedHistory(count: Int): List<PromptMessage> = (0 until count).map { index ->
        PromptMessage(
            if (index % 2 == 0) PromptRole.USER else PromptRole.ASSISTANT,
            "m${index.toString().padStart(2, '0')}",
        )
    }
}
