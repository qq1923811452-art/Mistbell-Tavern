package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatSendPlannerTest {

    private fun line(id: String, role: String, content: String, speaker: String? = null) = ChatLine(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = 0L,
        deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
        speakerName = speaker,
    )

    private val members = listOf(
        GroupPromptAssembler.Member("1", "露娜", "剑士"),
        GroupPromptAssembler.Member("2", "艾莉丝", ""),
    )

    @Test
    fun picksMentionedSpeakerAndBuildsPrompt() {
        val prepared = GroupChatSendPlanner.prepare(
            stableSystemPrefix = "Rules",
            members = members,
            history = listOf(line("a1", "assistant", "欢迎", "露娜")),
            draft = "@艾莉丝 你怎么看",
            lastSpeakerId = "1",
        )!!
        assertEquals("2", prepared.speakerId)
        assertEquals("艾莉丝", prepared.speakerName)
        assertTrue(prepared.messages.first().content.contains("本轮由「艾莉丝」回复"))
        assertEquals("[露娜] 欢迎", prepared.messages.first { it.role == PromptRole.ASSISTANT }.content)
    }

    @Test
    fun fallsBackToPreviousSpeakerWithoutMention() {
        val prepared = GroupChatSendPlanner.prepare(
            stableSystemPrefix = "Rules",
            members = members,
            history = emptyList(),
            draft = "继续",
            lastSpeakerId = "2",
        )!!
        assertEquals("2", prepared.speakerId)
    }

    @Test
    fun returnsNullWithoutMembersOrDraft() {
        assertNull(GroupChatSendPlanner.prepare("Rules", emptyList(), emptyList(), "hi", null))
        assertNull(GroupChatSendPlanner.prepare("Rules", members, emptyList(), "  ", null))
    }
}
