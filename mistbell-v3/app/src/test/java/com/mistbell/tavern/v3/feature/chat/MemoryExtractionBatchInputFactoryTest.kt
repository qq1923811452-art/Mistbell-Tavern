package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionQueueRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryExtractionBatchInputFactoryTest {
    private val factory = MemoryExtractionBatchInputFactory()

    @Test fun rebuildsExactlyTheQueuedTurnsInTheirPersistedOrder() {
        val job = job(listOf("user-2", "user-1"))
        val history = listOf(
            message("user-1", "user", "first question"),
            message("assistant-1", "assistant", "first answer"),
            message("user-2", "user", "second question"),
            message("assistant-2", "assistant", "second answer"),
        )

        val input = factory.create(job, history)

        assertEquals("conversation-7", input.conversationId)
        assertEquals(listOf("user-2", "user-1"), input.turns.map { it.id })
        assertEquals(1L, input.turns[0].ordinal)
        assertEquals(2L, input.turns[1].ordinal)
        assertEquals("User: second question\nAssistant: second answer", input.textByTurnId.getValue("user-2"))
        assertEquals("User: first question\nAssistant: first answer", input.textByTurnId.getValue("user-1"))
    }

    @Test fun missingQueuedTurnFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(job(listOf("missing")), listOf(message("other", "user", "text")))
        }
    }

    @Test fun incompleteAssistantReplyFailsClosed() {
        val history = listOf(
            message("user-1", "user", "question"),
            message(
                "assistant-1",
                "assistant",
                "partial answer",
                PersistedChatMessage.DeliveryState.FAILED,
            ),
        )
        assertThrows(MemoryExtractionQueueRunner.RetryableInputException::class.java) {
            factory.create(job(listOf("user-1")), history)
        }
    }

    @Test fun sourceTurnMustBeUserMessageWithFollowingAssistant() {
        val history = listOf(message("system-1", "system", "not a user turn"))
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(job(listOf("system-1")), history)
        }
    }

    private fun job(turnIds: List<String>) = MemoryExtractionJob(
        id = "job-1",
        conversationId = "conversation-7",
        turnIds = turnIds,
        providerProfileId = "memory-profile",
        credentialHandle = "opaque-handle",
        createdAtEpochMillis = 100L,
    )

    private fun message(
        id: String,
        role: String,
        content: String,
        state: PersistedChatMessage.DeliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
    ) = PersistedChatMessage(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = 100L,
        deliveryState = state,
    )
}
