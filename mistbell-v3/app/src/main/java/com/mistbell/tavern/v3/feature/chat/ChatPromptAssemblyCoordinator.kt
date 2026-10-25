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
        postHistoryInstructions: String? = null,
    ): List<PromptMessage> = PromptAssembler.assemble(
        PromptAssemblyInput(
            stableSystemPrefix = "你是 Mistbell Tavern 的聊天助手。请完整、详细地回应用户，写足场景、动作与对白，避免一句话收尾。",
            stableCharacterDefinition = characterDefinition,
            earlierConversationSummary = contextWindow.earlierSummary,
            worldbookEntries = activatedWorldbook,
            conversationHistory = contextWindow.recentMessages,
            latestUserMessage = latestUserMessage,
            postHistoryInstructions = postHistoryInstructions,
        ),
    )
}
