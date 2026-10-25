package com.mistbell.tavern.v3.feature.chat

import java.util.UUID

/**
 * Owns the existing opening-message insertion and pending user/assistant turn creation.
 */
internal class ChatTurnHistoryCoordinator {
    fun appendPendingTurn(
        history: MutableList<ChatLine>,
        openingMessage: String,
        userText: String,
    ) {
        // Chat history is persisted and reloaded ordered by (createdAt, id). IDs are random UUIDs,
        // so two messages created in the same millisecond can swap places after a restart. Keep
        // timestamps strictly increasing within the turn (and after the previous message) so the
        // user message always stays ahead of its assistant reply.
        var nextTimestamp = maxOf(
            System.currentTimeMillis(),
            (history.lastOrNull()?.createdAtEpochMillis ?: 0L) + 1L,
        )
        if (history.isEmpty() && openingMessage.isNotBlank()) {
            history += ChatLine(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = openingMessage,
                createdAtEpochMillis = nextTimestamp,
                deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
            )
            nextTimestamp += 1L
        }
        history += ChatLine(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = userText,
            createdAtEpochMillis = nextTimestamp,
            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
        )
        nextTimestamp += 1L
        history += ChatLine(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            content = "",
            createdAtEpochMillis = nextTimestamp,
            deliveryState = PersistedChatMessage.DeliveryState.PENDING,
        )
    }
}
