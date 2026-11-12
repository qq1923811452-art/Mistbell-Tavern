package com.mistbell.tavern.v3.feature.chat

/**
 * Holds the ordered stages used to send one chat turn.
 * Concrete stage coordinators remain responsible for validation, prompt assembly, and streaming.
 */
internal data class ChatSendTurnStages(
    val preflight: ChatSendRequestPreflightCoordinator,
    val turnStart: ChatSendTurnStartCoordinator,
    val promptPreparation: ChatTurnPromptPreparationCoordinator,
    val streamObserverFactory: ChatStreamObserverCoordinator,
    val streamFallback: ChatSendStreamFallbackCoordinator,
)
