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
            stableSystemPrefix = "你是 Mistbell Tavern 的聊天助手。" +
                "请根据用户的要求充分展开回答，适当描写场景、动作与对白，" +
                "保持自然连贯，不要无故过早结束。",
            stableCharacterDefinition = characterDefinition,
            earlierConversationSummary = contextWindow.earlierSummary,
            worldbookEntries = activatedWorldbook,
            conversationHistory = contextWindow.recentMessages,
            latestUserMessage = latestUserMessage,
            postHistoryInstructions = postHistoryInstructions,
        ),
    )
}
