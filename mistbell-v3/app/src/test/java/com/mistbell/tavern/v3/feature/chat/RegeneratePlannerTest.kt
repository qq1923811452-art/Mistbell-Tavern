package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegeneratePlannerTest {

    private fun line(id: String, role: String, content: String) = ChatLine(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = 0L,
        deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
    )

    @Test
    fun plansTheLastAssistantReply() {
        val history = listOf(
            line("u1", "user", "hello"),
            line("a1", "assistant", "hi"),
            line("u2", "user", "tell me more"),
            line("a2", "assistant", "more"),
        )
        val plan = RegeneratePlanner.plan(history, "a2")!!
        assertEquals("tell me more", plan.userText)
        assertEquals(3, plan.targetIndex)
        assertEquals(listOf("u1", "a1"), plan.prefixHistory.map { it.id })
    }

    @Test
    fun rejectsUserMessagesAndUnknownIds() {
        val history = listOf(line("u1", "user", "hello"), line("a1", "assistant", "hi"))
        assertNull(RegeneratePlanner.plan(history, "u1"))
        assertNull(RegeneratePlanner.plan(history, "missing"))
    }

    @Test
    fun requiresAPrecedingUserMessage() {
        val history = listOf(line("a1", "assistant", "orphan"))
        assertNull(RegeneratePlanner.plan(history, "a1"))
    }

    @Test
    fun rejectsBlankUserText() {
        val history = listOf(line("u1", "user", "   "), line("a1", "assistant", "hi"))
        assertNull(RegeneratePlanner.plan(history, "a1"))
    }
}
