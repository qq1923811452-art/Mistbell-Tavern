package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatHistoryStoreTest {
    @Test
    fun persistedMessageAcceptsSupportedRolesAndStates() {
        val message = PersistedChatMessage(
            id = "conversation-1-message-1",
            role = "assistant",
            content = "Hello",
            createdAtEpochMillis = 123L,
            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
        )

        assertEquals("assistant", message.role)
        assertEquals(PersistedChatMessage.DeliveryState.COMPLETE, message.deliveryState)
    }

    @Test
    fun persistedMessageRejectsUnknownRole() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatMessage(
                id = "message-1",
                role = "tool",
                content = "ignored",
                createdAtEpochMillis = 1L,
                deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
            )
        }
    }

    @Test
    fun persistedMessageRejectsNegativeTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatMessage(
                id = "message-1",
                role = "user",
                content = "Hello",
                createdAtEpochMillis = -1L,
                deliveryState = PersistedChatMessage.DeliveryState.PENDING,
            )
        }
    }

    @Test
    fun persistedMessageRejectsOversizedContent() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatMessage(
                id = "message-1",
                role = "user",
                content = "x".repeat(PersistedChatMessage.MAX_MESSAGE_CHARS + 1),
                createdAtEpochMillis = 1L,
                deliveryState = PersistedChatMessage.DeliveryState.PENDING,
            )
        }
    }
}
