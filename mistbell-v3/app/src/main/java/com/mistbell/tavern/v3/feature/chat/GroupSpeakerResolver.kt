package com.mistbell.tavern.v3.feature.chat

/**
 * One group-chat participant.
 *
 * [id] is the stable identity used for speaker attribution; [name] is the text users can
 * @-mention. Names may use Chinese characters, internal spaces and mixed case.
 */
data class GroupMember(
    val id: String,
    val name: String,
)

/**
 * The single speaker chosen for a group-chat turn, together with the rule that selected them.
 *
 * [speakerName] is the member name exactly as supplied in [GroupMember.name]; matching trims it,
 * but the returned label does not rewrite the caller's data.
 */
data class GroupSpeakerResolution(
    val speakerId: String,
    val speakerName: String,
    val reason: Reason,
) {
    /** Why [GroupSpeakerResolver] selected this speaker. Fallback reasons can drive a UI hint. */
    enum class Reason {
        /** A role name was mentioned with @ and matched that member. */
        MENTIONED,

        /** The message had no mention, so the previous speaker continues. */
        FALLBACK_PREVIOUS,

        /** The message had no mention and there was no previous speaker, so the first member speaks. */
        FALLBACK_FIRST,

        /** The first mention named no known member, so the previous speaker (or first member) speaks. */
        UNKNOWN_MENTION_FALLBACK,
    }
}

/**
 * Resolves exactly one speaker for a group-chat turn.
 *
 * Matching rules:
 * - A mention is `@` followed by a member name (whitespace between the sign and the name is
 *   ignored). The name comparison is exact, case-insensitive and ignores the member name's
 *   surrounding whitespace.
 * - A mention must end at a boundary: end of message, or a character that is not a letter, digit
 *   or underscore. Therefore `@露娜` does not match `@露娜二号`, and when one member name is a
 *   prefix of another (`Alice` / `Alice Chen`) the longest matching name wins.
 * - Only the first mention in the message is considered, even if it names no known member. This
 *   keeps "multiple mentions pick the first one" deterministic.
 * - Without a mention the previous speaker continues; without a previous speaker the first member
 *   speaks.
 * - An unknown mention falls back to the previous speaker (or the first member) and reports
 *   [GroupSpeakerResolution.Reason.UNKNOWN_MENTION_FALLBACK].
 *
 * A previous-speaker id that is no longer part of [members] counts as no history.
 * Empty member lists, or lists whose names are all blank, return null instead of throwing.
 */
object GroupSpeakerResolver {
    private const val MENTION_PREFIX = '@'
    private const val NAME_CONTINUATION_MARK = '_'

    /**
     * @param message the user message for the upcoming turn.
     * @param members participants eligible to speak, in display order.
     * @param previousSpeakerId the last speaking member's id, if any.
     * @return the single resolved speaker, or null when no usable member exists.
     */
    fun resolve(
        message: String,
        members: List<GroupMember>,
        previousSpeakerId: String? = null,
    ): GroupSpeakerResolution? {
        val usableMembers = members.filter { it.name.isNotBlank() }
        if (usableMembers.isEmpty()) return null
        val previous = usableMembers.firstOrNull { it.id == previousSpeakerId }
        val mentionStart = firstMentionStart(message)
        val mentioned = mentionStart?.let { bestMemberAt(message, it, usableMembers) }
        val speaker = mentioned ?: previous ?: usableMembers.first()
        val reason = when {
            mentioned != null -> GroupSpeakerResolution.Reason.MENTIONED
            mentionStart != null -> GroupSpeakerResolution.Reason.UNKNOWN_MENTION_FALLBACK
            previous != null -> GroupSpeakerResolution.Reason.FALLBACK_PREVIOUS
            else -> GroupSpeakerResolution.Reason.FALLBACK_FIRST
        }
        return GroupSpeakerResolution(
            speakerId = speaker.id,
            speakerName = speaker.name,
            reason = reason,
        )
    }

    /**
     * Index of the first `@` that is followed by a possible name character, skipping whitespace
     * between the sign and the name. A bare `@` is not a mention.
     */
    private fun firstMentionStart(message: String): Int? {
        var cursor = message.indexOf(MENTION_PREFIX)
        while (cursor >= 0) {
            val nameStart = skipWhitespace(message, cursor + 1)
            if (nameStart < message.length && isNameCharacter(message[nameStart])) return cursor
            cursor = message.indexOf(MENTION_PREFIX, cursor + 1)
        }
        return null
    }

    /**
     * Longest member name matching right after the mention sign. Names are compared against the
     * trimmed member name; the boundary check rejects a shorter name that continues as a word.
     */
    private fun bestMemberAt(message: String, mentionStart: Int, members: List<GroupMember>): GroupMember? {
        val nameStart = skipWhitespace(message, mentionStart + 1)
        var best: GroupMember? = null
        var bestNameLength = 0
        for (member in members) {
            val name = member.name.trim()
            val isLongerMatch = name.length > bestNameLength && matchesAt(message, nameStart, name)
            if (isLongerMatch) {
                best = member
                bestNameLength = name.length
            }
        }
        return best
    }

    private fun matchesAt(message: String, start: Int, name: String): Boolean {
        val nameEnd = start + name.length
        if (nameEnd > message.length) return false
        val matched = message.regionMatches(start, name, 0, name.length, ignoreCase = true)
        return matched && !isNameCharacter(message, nameEnd)
    }

    /** True when [index] is a name character, so a name ending here would be a partial word. */
    private fun isNameCharacter(message: String, index: Int): Boolean =
        index < message.length &&
            (message[index].isLetterOrDigit() || message[index] == NAME_CONTINUATION_MARK)

    private fun skipWhitespace(message: String, start: Int): Int {
        var index = start
        while (index < message.length && message[index].isWhitespace()) index++
        return index
    }
}
