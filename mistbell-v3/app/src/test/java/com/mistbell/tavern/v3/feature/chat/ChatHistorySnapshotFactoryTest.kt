package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistorySnapshotFactoryTest {
    @Test
    fun snapshotPreservesGroupSpeakerAndSwipeVariants() {
        val source =
            ChatLine(
                id = "assistant-1",
                role = "assistant",
                content = "second reply",
                createdAtEpochMillis = 1L,
                deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
                speakerId = "character-7",
                speakerName = "Luna",
                variants = listOf("first reply", "second reply"),
                activeVariant = 1,
            )

        val persisted = ChatHistorySnapshotFactory.create(listOf(source)).single()
        val restored = ChatHistorySnapshotFactory.restore(listOf(persisted)).single()

        assertEquals("character-7", persisted.speakerId)
        assertEquals("Luna", persisted.speakerName)
        assertEquals(listOf("first reply", "second reply"), restored.variants)
        assertEquals(1, restored.activeVariant)
        assertEquals("Luna", restored.speakerName)
    }
}
