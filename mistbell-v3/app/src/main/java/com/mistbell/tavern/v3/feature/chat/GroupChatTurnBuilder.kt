package com.mistbell.tavern.v3.feature.chat

/**
 * Turns a group conversation into the prompt for the next speaker.
 *
 * The system block names the members and the current speaker; assistant history is rendered with
 * the speaker prefix so every earlier line stays attributed. Single-character chats do not use
 * this path.
 */
internal object GroupChatTurnBuilder {

    fun build(
        stableSystemPrefix: String,
        members: List<GroupPromptAssembler.Member>,
        speakerName: String,
        history: List<ChatLine>,
        latestUserMessage: String,
    ): List<PromptMessage> {
        require(stableSystemPrefix.isNotBlank()) { "A stable system prefix is required." }
        require(latestUserMessage.isNotBlank()) { "The latest user message is required." }
        val system = buildString {
            append(stableSystemPrefix.trim())
            append("\n\n")
            append(GroupPromptAssembler.systemBlock(members, speakerName))
        }
        val messages = mutableListOf(PromptMessage(PromptRole.SYSTEM, system))
        history.forEach { line ->
            when (line.role) {
                "user" -> messages += PromptMessage(PromptRole.USER, line.content)
                "assistant" -> messages += PromptMessage(
                    PromptRole.ASSISTANT,
                    GroupPromptAssembler.labelAssistantContent(line.speakerName, line.activeContent),
                )
            }
        }
        messages += PromptMessage(PromptRole.USER, latestUserMessage.trim())
        return messages
    }
}
