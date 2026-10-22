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
        val resolution = if (members.isEmpty() || draft.isBlank()) {
            null
        } else {
            GroupSpeakerResolver.resolve(
                message = draft,
                members = members.map { GroupMember(it.id, it.name) },
                previousSpeakerId = lastSpeakerId,
            )
        }
        return resolution?.let { speaker ->
            Prepared(
                speakerId = speaker.speakerId,
                speakerName = speaker.speakerName,
                messages = GroupChatTurnBuilder.build(
                    stableSystemPrefix = stableSystemPrefix,
                    members = members,
                    speakerName = speaker.speakerName,
                    history = history,
                    latestUserMessage = draft,
                ),
            )
        }
    }
}
