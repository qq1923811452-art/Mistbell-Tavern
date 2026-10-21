package com.mistbell.tavern.v3.feature.chat

internal object ChatHistorySnapshotFactory {
    fun create(history: List<ChatLine>): List<PersistedChatMessage> =
        history.map { line ->
            PersistedChatMessage(
                id = line.id,
                role = line.role,
                content = line.content,
                createdAtEpochMillis = line.createdAtEpochMillis,
                deliveryState = line.deliveryState,
            )
        }

    fun restore(messages: List<PersistedChatMessage>): List<ChatLine> =
        messages.map { message ->
            ChatLine(
                id = message.id,
                role = message.role,
                content = message.content,
                createdAtEpochMillis = message.createdAtEpochMillis,
                deliveryState = message.deliveryState,
            )
        }
}
