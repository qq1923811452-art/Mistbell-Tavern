package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatTurnBuilderTest {

    private fun line(id: String, role: String, content: String, speaker: String? = null) = ChatLine(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = 0L,
        deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
        speakerName = speaker,
    )

    @Test
    fun buildsSystemBlockAndLabelsAssistantHistory() {
        val messages = GroupChatTurnBuilder.build(
            stableSystemPrefix = "Rules",
            members = listOf(
                GroupPromptAssembler.Member("1", "露娜", "剑士"),
                GroupPromptAssembler.Member("2", "艾莉丝", ""),
            ),
            speakerName = "艾莉丝",
            history = listOf(
                line("u1", "user", "大家好"),
                line("a1", "assistant", "欢迎回来", "露娜"),
            ),
            latestUserMessage = "@艾莉丝 你怎么看",
        )
        assertEquals(PromptRole.SYSTEM, messages.first().role)
        assertTrue(messages.first().content.contains("Rules"))
        assertTrue(messages.first().content.contains("本轮由「艾莉丝」回复"))
        val labeled = messages.first { it.role == PromptRole.ASSISTANT }
        assertEquals("[露娜] 欢迎回来", labeled.content)
        assertEquals("@艾莉丝 你怎么看", messages.last().content)
        assertEquals(PromptRole.USER, messages.last().role)
    }

    @Test
    fun keepsAssistantLinesWithoutSpeakerUnlabeled() {
        val messages = GroupChatTurnBuilder.build(
            stableSystemPrefix = "Rules",
            members = listOf(GroupPromptAssembler.Member("1", "露娜", "")),
            speakerName = "露娜",
            history = listOf(line("a1", "assistant", "旧回复")),
            latestUserMessage = "继续",
        )
        assertEquals("旧回复", messages.first { it.role == PromptRole.ASSISTANT }.content)
    }

    @Test
    fun skipsSystemHistoryLines() {
        val messages = GroupChatTurnBuilder.build(
            stableSystemPrefix = "Rules",
            members = emptyList(),
            speakerName = "露娜",
            history = listOf(line("s1", "system", "ignored")),
            latestUserMessage = "hi",
        )
        assertEquals(2, messages.size)
    }
}
