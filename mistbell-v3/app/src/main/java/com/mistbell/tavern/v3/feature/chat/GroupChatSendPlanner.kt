package com.mistbell.tavern.v3.feature.chat

/**
 * Prepares one group turn: picks the speaker for this message and assembles the prompt.
 *
 * Returns null when the session has no usable members so callers can fall back to normal chat.
 */
internal object GroupChatSendPlanner {

    data class Prepared(
        val speakerId: String,
        val speakerName: String,
        val messages: List<PromptMessage>,
    )

    fun prepare(
        stableSystemPrefix: String,
        members: List<GroupPromptAssembler.Member>,
        history: List<ChatLine>,
        draft: String,
        lastSpeakerId: String?,
    ): Prepared? {
        if (members.isEmpty() || draft.isBlank()) return null
        val resolution = GroupSpeakerResolver.resolve(
            message = draft,
            members = members.map { GroupMember(it.id, it.name) },
            previousSpeakerId = lastSpeakerId,
        ) ?: return null
        val messages = GroupChatTurnBuilder.build(
            stableSystemPrefix = stableSystemPrefix,
            members = members,
            speakerName = resolution.speakerName,
            history = history,
            latestUserMessage = draft,
        )
        return Prepared(resolution.speakerId, resolution.speakerName, messages)
    }
}
