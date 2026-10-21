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
}
