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
        cardSystemInstructions: String? = null,
    ): List<PromptMessage> = PromptAssembler.assemble(
        PromptAssemblyInput(
            stableSystemPrefix = if (characterDefinition.isNullOrBlank()) {
                "你是 Mistbell Tavern 的聊天助手。根据当前用户的问题完整、自然地回答，不无故提前结束，也不堆砌内容。"
            } else {
                "你在 Mistbell Tavern 中扮演当前角色卡指定的人物。以该角色的动机、知识和表达方式回应当前情境，" +
                    "维持剧情连续性，不以通用助手口吻代替角色，不机械添加场景描写。"
            },
            stableCharacterDefinition = characterDefinition,
            earlierConversationSummary = contextWindow.earlierSummary,
            worldbookEntries = activatedWorldbook,
            conversationHistory = contextWindow.recentMessages,
            pinnedPrefixMessages = contextWindow.pinnedPrefixMessages,
            latestUserMessage = latestUserMessage,
            postHistoryInstructions = postHistoryInstructions,
            cardSystemInstructions = cardSystemInstructions,
        ),
    )
}
