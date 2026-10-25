package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPromptAssemblerTest {
    @Test
    fun systemBlockDescribesSingleMemberAndTurnSpeaker() {
        val expected = """
            这是一个多角色群聊；每轮只由一个角色回复；请保持各自的人设，不要代替其他角色发言。

            群成员：
            - 露娜：冷静的剑士

            本轮由「露娜」回复。
        """.trimIndent()

        val block = GroupPromptAssembler.systemBlock(
            members = listOf(member(id = "luna", name = "露娜", brief = "冷静的剑士")),
            speakerName = "露娜",
        )

        assertEquals(expected, block)
    }

    @Test
    fun systemBlockListsEveryMemberInOrder() {
        val block = GroupPromptAssembler.systemBlock(
            members = listOf(
                member(id = "luna", name = "露娜", brief = "冷静的剑士"),
                member(id = "bob", name = "Bob", brief = "开朗的吟游诗人"),
            ),
            speakerName = "Bob",
        )

        assertTrue(block.contains("群成员：\n- 露娜：冷静的剑士\n- Bob：开朗的吟游诗人"))
        assertTrue(block.endsWith("\n\n本轮由「Bob」回复。"))
    }

    @Test
    fun systemBlockRendersMemberWithoutBriefAsNameOnly() {
        val block = GroupPromptAssembler.systemBlock(
            members = listOf(member(id = "luna", name = "露娜", brief = "   ")),
            speakerName = "露娜",
        )

        assertTrue(block.contains("群成员：\n- 露娜\n\n本轮由「露娜」回复。"))
        assertFalse(block.contains("露娜："))
    }

    @Test
    fun systemBlockStillNamesSpeakerOutsideTheMemberList() {
        val block = GroupPromptAssembler.systemBlock(
            members = listOf(member(id = "luna", name = "露娜", brief = "冷静的剑士")),
            speakerName = "旁白",
        )

        assertTrue(block.contains("- 露娜：冷静的剑士"))
        assertTrue(block.endsWith("本轮由「旁白」回复。"))
    }

    @Test
    fun systemBlockRemainsUsableWithNoMembers() {
        val block = GroupPromptAssembler.systemBlock(members = emptyList(), speakerName = "露娜")

        assertTrue(block.contains("群成员：\n（暂无可用成员）"))
        assertTrue(block.endsWith("本轮由「露娜」回复。"))
    }

    @Test
    fun systemBlockSkipsBlankMemberNames() {
        val block = GroupPromptAssembler.systemBlock(
            members = listOf(
                member(id = "blank", name = "   ", brief = "无名人设"),
                member(id = "bob", name = "Bob", brief = "吟游诗人"),
            ),
            speakerName = "Bob",
        )

        assertTrue(block.contains("- Bob：吟游诗人"))
        assertFalse(block.contains("无名人设"))
    }

    @Test
    fun systemBlockNormalisesWhitespaceInNamesBriefsAndSpeaker() {
        val block = GroupPromptAssembler.systemBlock(
            members = listOf(
                member(id = "luna", name = "  露娜\n二号  ", brief = "  冷静的\n\t剑士  "),
            ),
            speakerName = " 露娜\n二号 ",
        )

        assertTrue(block.contains("- 露娜 二号：冷静的 剑士"))
        assertTrue(block.endsWith("本轮由「露娜 二号」回复。"))
    }

    @Test
    fun systemBlockUsesGenericSpeakerLineWhenSpeakerIsBlank() {
        val block = GroupPromptAssembler.systemBlock(
            members = listOf(member(id = "luna", name = "露娜", brief = "剑士")),
            speakerName = "   ",
        )

        assertTrue(block.endsWith("本轮由群成员回复。"))
    }

    @Test
    fun labelReturnsContentUnchangedWhenSpeakerIsMissing() {
        assertEquals(" 你好 ", label(speakerName = null, content = " 你好 "))
        assertEquals("\n[露娜] 你好", label(speakerName = "   ", content = "\n[露娜] 你好"))
    }

    @Test
    fun labelDoesNotRepeatAnExistingPrefix() {
        assertEquals("[露娜] 你好", label(speakerName = "露娜", content = "[露娜] 你好"))
        assertEquals("[露娜]你好", label(speakerName = "露娜", content = "[露娜]你好"))
        assertEquals("[露娜] 你好", label(speakerName = " 露娜 ", content = "\n  [露娜] 你好\n"))
    }

    @Test
    fun labelAddsSpeakerPrefix() {
        assertEquals("[Bob] 晚上好", label(speakerName = "Bob", content = "晚上好"))
        assertEquals("[露娜] 你好", label(speakerName = " 露娜 ", content = "\n你好\n"))
    }

    @Test
    fun labelDoesNotTreatALongerNameAsTheSamePrefix() {
        assertEquals(
            "[露娜] [露娜二号] 你好",
            label(speakerName = "露娜", content = "[露娜二号] 你好"),
        )
    }

    @Test
    fun labelKeepsTheLabelWhenContentIsBlank() {
        assertEquals("[露娜]", label(speakerName = "露娜", content = "  \n "))
    }

    @Test
    fun labelHistoryAssistantMessagesSkipsSyntheticDepthInsertions() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, "Rules"),
            PromptMessage(PromptRole.ASSISTANT, "Lore aside.", synthetic = true),
            PromptMessage(PromptRole.USER, "Earlier question"),
            PromptMessage(PromptRole.ASSISTANT, "Earlier answer"),
            PromptMessage(PromptRole.USER, "Latest question"),
        )

        val labelled = GroupPromptAssembler.labelHistoryAssistantMessages(
            messages,
            assistantNames = listOf("露娜"),
        )

        assertEquals("Lore aside.", labelled[1].content)
        assertEquals("[露娜] Earlier answer", labelled[3].content)
    }

    @Test
    fun labelHistoryAssistantMessagesConsumesNamesInHistoryOrder() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, "Rules"),
            PromptMessage(PromptRole.ASSISTANT, "First"),
            PromptMessage(PromptRole.USER, "Question"),
            PromptMessage(PromptRole.ASSISTANT, "Second"),
            PromptMessage(PromptRole.USER, "Latest"),
        )

        val labelled = GroupPromptAssembler.labelHistoryAssistantMessages(
            messages,
            assistantNames = listOf("露娜", "Bob"),
        )

        assertEquals("[露娜] First", labelled[1].content)
        assertEquals("[Bob] Second", labelled[3].content)
    }

    @Test
    fun labelHistoryAssistantMessagesAlignsNamesToARetainedHistoryWindow() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, "Rules"),
            PromptMessage(PromptRole.USER, "Recent question"),
            PromptMessage(PromptRole.ASSISTANT, "Bob reply"),
            PromptMessage(PromptRole.USER, "Latest question"),
        )

        val labelled = GroupPromptAssembler.labelHistoryAssistantMessages(
            messages,
            assistantNames = listOf("Alice", "Bob"),
        )

        assertEquals("[Bob] Bob reply", labelled[2].content)
    }

    private fun member(id: String, name: String, brief: String = "") =
        GroupPromptAssembler.Member(id = id, name = name, brief = brief)

    private fun label(speakerName: String?, content: String) =
        GroupPromptAssembler.labelAssistantContent(speakerName, content)
}
