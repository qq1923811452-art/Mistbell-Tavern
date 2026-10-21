package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry

/**
 * Owns assembly of the existing stable system prefix and per-turn prompt sections.
 */
internal class ChatPromptAssemblyCoordinator {
    fun assemble(
        characterDefinition: String?,
        contextWindow: ConversationContextWindow,
        activatedWorldbook: List<WorldBookEntry>,
        latestUserMessage: String,
    ): List<PromptMessage> = PromptAssembler.assemble(
        PromptAssemblyInput(
            stableSystemPrefix = "你是 Mistbell Tavern 的聊天助手。请直接、清晰地回答用户。",
            stableCharacterDefinition = characterDefinition,
            earlierConversationSummary = contextWindow.earlierSummary,
            worldbookEntries = activatedWorldbook,
            conversationHistory = contextWindow.recentMessages,
            latestUserMessage = latestUserMessage,
        ),
    )
}
