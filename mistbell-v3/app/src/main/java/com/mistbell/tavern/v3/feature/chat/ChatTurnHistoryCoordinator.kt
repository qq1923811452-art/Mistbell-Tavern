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
        if (history.isEmpty() && openingMessage.isNotBlank()) {
            history += ChatLine(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = openingMessage,
                createdAtEpochMillis = System.currentTimeMillis(),
                deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
            )
        }
        val now = System.currentTimeMillis()
        history += ChatLine(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = userText,
            createdAtEpochMillis = now,
            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
        )
        history += ChatLine(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            content = "",
            createdAtEpochMillis = now,
            deliveryState = PersistedChatMessage.DeliveryState.PENDING,
        )
    }
}
