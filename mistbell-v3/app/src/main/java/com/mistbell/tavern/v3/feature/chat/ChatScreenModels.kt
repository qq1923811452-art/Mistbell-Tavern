package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.character.CharacterCard

/**
 * Existing chat-screen models and helpers moved out of ChatScreen.
 * This extraction intentionally preserves their behavior and data shape.
 */
internal fun formatCharacterCardForPrompt(card: CharacterCard): String = card.stableDefinition()

internal val CHAT_EMBEDDING_DIMENSION_OPTIONS = listOf(128, 256, 512, 768, 1024, 1536, 2048)

internal data class ChatLine(
    val id: String,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val deliveryState: PersistedChatMessage.DeliveryState,
)
