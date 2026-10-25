package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnHistoryCoordinatorTest {

    private fun existingLine(id: String, createdAt: Long) = ChatLine(
        id = id,
        role = "assistant",
        content = "",
        createdAtEpochMillis = createdAt,
        deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
    )

    @Test
    fun keepsUserBeforeAssistantWithStrictlyIncreasingTimestamps() {
        val history = mutableListOf<ChatLine>()

        ChatTurnHistoryCoordinator().appendPendingTurn(history, "开场", "你好")

        assertEquals(listOf("assistant", "user", "assistant"), history.map { it.role })
        assertStrictlyIncreasing(history)
    }

    @Test
    fun continuesAfterExistingHistoryEvenWhenTheClockWouldCollide() {
        val future = System.currentTimeMillis() + 60_000L
        val history = mutableListOf(existingLine("a0", future))

        ChatTurnHistoryCoordinator().appendPendingTurn(history, "", "继续")

        assertEquals(listOf("assistant", "user", "assistant"), history.map { it.role })
        assertTrue(history[1].createdAtEpochMillis > future)
        assertTrue(history[2].createdAtEpochMillis > history[1].createdAtEpochMillis)
    }

    private fun assertStrictlyIncreasing(history: List<ChatLine>) {
        for (index in 1 until history.size) {
            assertTrue(
                "timestamps must increase so SQLite ordering stays stable",
                history[index].createdAtEpochMillis > history[index - 1].createdAtEpochMillis,
            )
        }
    }
}
