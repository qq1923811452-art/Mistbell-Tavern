package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupSpeakerResolverTest {
    private val members = listOf(
        GroupMember(id = "luna", name = "露娜"),
        GroupMember(id = "bob", name = "Bob"),
    )

    @Test
    fun resolvesMentionedMemberExactly() {
        val resolution = resolve("@露娜 你好", members)
        assertEquals("luna", resolution.speakerId)
        assertEquals("露娜", resolution.speakerName)
        assertEquals(GroupSpeakerResolution.Reason.MENTIONED, resolution.reason)
    }

    @Test
    fun ignoresCaseAndSurroundingWhitespaceWhenMatchingNames() {
        val padded = listOf(GroupMember(id = "bob", name = "  Bob  "))
        assertEquals("bob", resolve("@bob 你好", padded).speakerId)
        assertEquals("bob", resolve("@BOB 你好", padded).speakerId)
        assertEquals("bob", resolve("@ Bob 你好", padded).speakerId)
    }

    @Test
    fun takesTheFirstMentionWhenSeveralMembersAreMentioned() {
        assertEquals("bob", resolve("@Bob 你觉得呢？@露娜 你怎么看？", members).speakerId)
        assertEquals("luna", resolve("@露娜 @Bob 一起吗", members).speakerId)
    }

    @Test
    fun fallsBackToPreviousSpeakerWhenThereIsNoMention() {
        val resolution = resolve("继续", members, previousSpeakerId = "bob")
        assertEquals("bob", resolution.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.FALLBACK_PREVIOUS, resolution.reason)
    }

    @Test
    fun fallsBackToFirstMemberWhenThereIsNoMentionOrHistory() {
        val resolution = resolve("继续", members)
        assertEquals("luna", resolution.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.FALLBACK_FIRST, resolution.reason)
    }

    @Test
    fun fallsBackWithReasonWhenMentionedMemberDoesNotExist() {
        val withHistory = resolve("@不存在的角色 你好", members, previousSpeakerId = "bob")
        assertEquals("bob", withHistory.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.UNKNOWN_MENTION_FALLBACK, withHistory.reason)

        val withoutHistory = resolve("@不存在的角色 你好", members)
        assertEquals("luna", withoutHistory.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.UNKNOWN_MENTION_FALLBACK, withoutHistory.reason)
    }

    @Test
    fun returnsNullWhenNoMemberCanSpeak() {
        assertNull(GroupSpeakerResolver.resolve("@露娜 你好", emptyList()))
        assertNull(GroupSpeakerResolver.resolve("@露娜 你好", listOf(GroupMember(id = "blank", name = "   "))))
    }

    @Test
    fun considersOnlyTheFirstMentionEvenWhenALaterOneMatches() {
        val resolution = resolve("@不存在的角色 @Bob 你好", members, previousSpeakerId = "luna")
        assertEquals("luna", resolution.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.UNKNOWN_MENTION_FALLBACK, resolution.reason)
    }

    @Test
    fun prefersLongerNameOverShorterPrefixName() {
        val overlapping = listOf(
            GroupMember(id = "short", name = "Alice"),
            GroupMember(id = "long", name = "Alice Chen"),
        )
        assertEquals("long", resolve("@Alice Chen 你好", overlapping).speakerId)
        assertEquals("short", resolve("@Alice 你好", overlapping).speakerId)
    }

    @Test
    fun prefersLongerChineseNameOverShorterPrefixName() {
        val overlapping = listOf(
            GroupMember(id = "luna", name = "露娜"),
            GroupMember(id = "luna2", name = "露娜二号"),
        )
        assertEquals("luna2", resolve("@露娜二号 你好", overlapping).speakerId)
        assertEquals("luna", resolve("@露娜 你好", overlapping).speakerId)
    }

    @Test
    fun doesNotMatchShorterNameInsideALongerUnknownName() {
        val onlyShort = listOf(GroupMember(id = "luna", name = "露娜"))
        val resolution = resolve("@露娜二号 你好", onlyShort)
        assertEquals("luna", resolution.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.UNKNOWN_MENTION_FALLBACK, resolution.reason)
    }

    @Test
    fun matchesMemberNamesContainingSpaces() {
        val spaced = listOf(GroupMember(id = "chen", name = "Alice Chen"))
        assertEquals("chen", resolve("@Alice Chen，你好", spaced).speakerId)
    }

    @Test
    fun matchesMentionFollowedByPunctuation() {
        assertEquals("luna", resolve("你好@露娜，今天怎么样？", members).speakerId)
    }

    @Test
    fun ignoresAStalePreviousSpeakerId() {
        val resolution = resolve("继续", members, previousSpeakerId = "removed")
        assertEquals("luna", resolution.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.FALLBACK_FIRST, resolution.reason)
    }

    @Test
    fun treatsBareAtSignAsNoMention() {
        val resolution = resolve("价格 @", members, previousSpeakerId = "bob")
        assertEquals("bob", resolution.speakerId)
        assertEquals(GroupSpeakerResolution.Reason.FALLBACK_PREVIOUS, resolution.reason)
    }

    private fun resolve(
        message: String,
        participants: List<GroupMember>,
        previousSpeakerId: String? = null,
    ): GroupSpeakerResolution =
        requireNotNull(GroupSpeakerResolver.resolve(message, participants, previousSpeakerId))
}
