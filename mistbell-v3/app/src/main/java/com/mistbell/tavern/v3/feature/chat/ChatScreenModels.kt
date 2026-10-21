package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.character.CharacterCard

/**
 * Chat screen's existing presentation models and small formatting/configuration helpers.
 *
 * Declarations are shared with ChatScreen after being moved out of that file.
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
