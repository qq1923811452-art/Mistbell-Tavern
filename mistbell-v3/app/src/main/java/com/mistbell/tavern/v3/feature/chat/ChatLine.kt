package com.mistbell.tavern.v3.feature.chat

internal data class ChatLine(
    val id: String,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val deliveryState: PersistedChatMessage.DeliveryState,
    /** Group chats only: which participant produced this assistant message. */
    val speakerId: String? = null,
    val speakerName: String? = null,
)
