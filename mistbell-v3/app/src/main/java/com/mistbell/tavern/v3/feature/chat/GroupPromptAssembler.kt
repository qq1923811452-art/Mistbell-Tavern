package com.mistbell.tavern.v3.feature.chat

/**
 * Pure text helpers for the multi-character group-chat prompt.
 *
 * The assembler neither reads nor mutates chat state: callers decide where the returned text is
 * inserted into the request. Display names are always trimmed before use.
 */
object GroupPromptAssembler {
    /** One participant rendered in the group-chat member list. */
    data class Member(
        val id: String,
        val name: String,
        val brief: String,
    )

    private const val RULES =
        "这是一个多角色群聊；每轮只由一个角色回复；请保持各自的人设，不要代替其他角色发言。"
    private const val MEMBER_HEADER = "群成员："
    private const val NO_MEMBERS = "（暂无可用成员）"
    private const val GENERIC_SPEAKER_LINE = "本轮由群成员回复。"
    private val INLINE_WHITESPACE = Regex("\\s+")

    /**
     * Builds the system block that describes the group and names this turn's speaker.
     *
     * Members keep the supplied order and render as `- name：brief`; a blank [Member.brief]
     * renders as `- name` only. Names and briefs are trimmed, and internal runs of whitespace
     * (including newlines) collapse to one space so each member occupies one line. Members with a
     * blank name are skipped. A speaker missing from [members] is still named in the closing line,
     * and an empty [members] list renders a placeholder, so this method never throws for missing
     * participants.
     */
    fun systemBlock(members: List<Member>, speakerName: String): String {
        val renderedMembers = members.mapNotNull { member ->
            val name = singleLine(member.name)
            if (name.isEmpty()) null else name to singleLine(member.brief)
        }
        val memberList = if (renderedMembers.isEmpty()) {
            NO_MEMBERS
        } else {
            renderedMembers.joinToString("\n") { (name, brief) ->
                if (brief.isEmpty()) "- $name" else "- $name：$brief"
            }
        }
        val speaker = singleLine(speakerName)
        val speakerLine = if (speaker.isEmpty()) GENERIC_SPEAKER_LINE else "本轮由「$speaker」回复。"
        return listOf(RULES, "$MEMBER_HEADER\n$memberList", speakerLine).joinToString("\n\n")
    }

    /**
     * Prefixes assistant content with the speaker label unless the content already carries it.
     *
     * A null or blank [speakerName] returns [content] unchanged, which keeps classic
     * single-character history intact. Otherwise the label is built from the trimmed name and the
     * trimmed content: `[name] content`. Content that already starts with `[name]` is not labelled
     * twice. Blank content keeps the label alone (`[name]`). While a label is applied, one leading
     * and trailing run of whitespace around [content] is removed, including an existing label's
     * surrounding whitespace.
     */
    fun labelAssistantContent(speakerName: String?, content: String): String {
        val name = singleLine(speakerName.orEmpty())
        if (name.isEmpty()) return content
        val trimmedContent = content.trim()
        return when {
            trimmedContent.startsWith("[$name]") -> trimmedContent
            trimmedContent.isEmpty() -> "[$name]"
            else -> "[$name] $trimmedContent"
        }
    }

    /**
     * Labels real assistant history messages with their speaker names while leaving derived
     * messages (worldbook depth insertions, marked `synthetic`) untouched. Synthetic assistant
     * messages are not part of the conversation and must not consume a history speaker name.
     *
     * Callers may pass names from the full history even when the prompt only keeps its newest
     * window. The returned labels align to the retained messages by taking the last names.
     */
    fun labelHistoryAssistantMessages(
        messages: List<PromptMessage>,
        assistantNames: List<String?>,
    ): List<PromptMessage> {
        val retainedAssistantCount = messages.count {
            it.role == PromptRole.ASSISTANT && !it.synthetic
        }
        val windowedNames = assistantNames.takeLast(retainedAssistantCount)
        var assistantIndex = 0
        return messages.map { message ->
            if (message.role != PromptRole.ASSISTANT || message.synthetic) {
                message
            } else {
                val name = windowedNames.getOrNull(assistantIndex)
                assistantIndex += 1
                message.copy(content = labelAssistantContent(name, message.content))
            }
        }
    }

    /** Trims [text] and replaces each internal whitespace run with a single space. */
    private fun singleLine(text: String): String = text.trim().replace(INLINE_WHITESPACE, " ")
}
