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
    /** All generated alternatives for this assistant message; index 0 is the first reply. */
    val variants: List<String> = emptyList(),
    val activeVariant: Int = 0,
) {
    /** Text of the currently selected alternative, falling back to [content]. */
    val activeContent: String
        get() = variants.getOrNull(activeVariant) ?: content
}
