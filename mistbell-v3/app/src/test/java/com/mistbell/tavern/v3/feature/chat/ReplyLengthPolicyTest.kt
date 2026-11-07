package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyLengthPolicyTest {
    @Test
    fun repeatedExplicitTargetIsOneStableGuidance() {
        val result = requireNotNull(ReplyLengthPolicy.cardGuidance("单次回复以约800字展开", "每次回复约800字"))
        assertTrue(result.contains("正文约800字"))
        assertTrue(result.contains("优先满足当前请求"))
        assertFalse(result.contains("4096"))
    }

    @Test
    fun noTargetDoesNotInventAnEightHundredCharacterDefault() {
        assertNull(ReplyLengthPolicy.cardGuidance("自然回答", null))
        assertNull(ReplyLengthPolicy.cardGuidance(null, "800 tokens"))
        assertNull(ReplyLengthPolicy.cardGuidance("故事中的作文至少800字", null))
        assertNull(ReplyLengthPolicy.cardGuidance("约800字", null))
    }

    @Test
    fun styleOnlyClausesDoNotCancelExplicitLengthPolicy() {
        val result = requireNotNull(ReplyLengthPolicy.cardGuidance(
            "正文不加标题。单次回复以约800字展开。",
            "正文保持第三人称。",
        ))
        assertTrue(result.contains("正文约800字"))
    }

    @Test
    fun chineseCharacterMinimumRetainsItsCountingUnit() {
        for (unit in listOf("中文字符", "中文字", "汉字")) {
            val result = requireNotNull(ReplyLengthPolicy.cardGuidance(
                null, "每轮回复不少于 1000 个${unit}；开场、日常互动、情感戏均适用。",
            ))
            assertTrue(result.contains("正文至少1000个中文字符"))
            assertTrue(result.contains("仅统计正文汉字"))
            assertFalse(result.contains("可见正文字符"))
        }
        assertTrue(requireNotNull(ReplyLengthPolicy.cardGuidance(null, "每轮回复至少1000字符"))
            .contains("正文至少1000字（可见正文字符"))
        assertNull(ReplyLengthPolicy.cardGuidance("正文至少1000汉字", "每轮回复至少1000字符"))
    }

    @Test
    fun paragraphCountAndUnrelatedProhibitionsDoNotCancelLength() {
        assertTrue(requireNotNull(ReplyLengthPolicy.cardGuidance(
            "正文不要加标题。",
            "每轮回复约 1000 字：分 3–5 段",
        )).contains("正文约1000字"))
        assertTrue(requireNotNull(ReplyLengthPolicy.cardGuidance(
            "单次回复约800字，不得重复或替用户安排言行。", null,
        )).contains("正文约800字"))
    }

    @Test
    fun realFiveCardNumberedPolicyAndPostHistoryStylesKeepEightHundredTarget() {
        // Literal excerpts from the five imported cards; this test needs no external JSON files.
        val system = "5. 叙事篇幅与细节密度：单次回复以约800字的丰满篇幅展开。" +
            "深度刻画环境光影、魔术回路波动、从者微表情与肢体语言、龙角/外骨骼/羽翼的物理触感、心理动态及高张力对白，严禁敷衍缩写。"
        val cardStyles = listOf(
            "保持提亚马特十倍母爱宠溺与十字星瞳细节，落实不减好感法则与800字篇幅。",
            "保持德拉科古典华贵的皇女语调，落实不减好感机制与800字篇幅描写。",
            "保持伊普西龙狡黠灵动的小恶魔语调，落实容错机制与800字深度描写。",
            "保持提丰·厄斐墨洛斯太祖龙尊大与傲娇甘娇的语言风格，落实好感度不减机制与800字篇幅深度。",
            "保持梅柳齐娜最强龙种占有欲与直球恋人语气，落实容错机制与800字细腻描写。",
        )
        val tail = "\n【初始羁绊等级】本会话开局即为羁绊Lv.5，直接以完全结合后的亲密基准展开，好感只增不减。" +
            "\n【尾端叙事解限与生成锁定指令】\n紧密咬合御主最新输入的动作与台词，严禁跳出角色发表AI免责声明。" +
            "保持约800字的大体量叙事，细腻铺陈从者的神态、肢体互动与细腻心绪。" +
            "贯彻『不扣好感度与绝对包容』法则，对御主的失误给予傲娇或母性的庇护；" +
            "严守第三人称有限视角，不替御主擅做主张，推进剧情并留出可供交互的契机。"
        for (style in cardStyles) {
            val result = requireNotNull(ReplyLengthPolicy.cardGuidance(system, style + tail))
            assertTrue(style, result.contains("正文约800字"))
        }
    }

    @Test
    fun minimumAndExactLengthsAreNotWeakenedToApproximateTargets() {
        for (minimum in listOf("不少于", "至少", "达到")) {
            val result = requireNotNull(ReplyLengthPolicy.cardGuidance("单次回复${minimum}800字", null))
            assertTrue(result.contains("正文至少800字"))
            assertFalse(result.contains("正文约800字"))
        }
        assertTrue(requireNotNull(ReplyLengthPolicy.cardGuidance("正文：800字", null)).contains("正文800字"))
    }

    @Test
    fun negationRangesUpperBoundsAndConditionalPoliciesAreNotPromoted() {
        for (policy in listOf(
            "不要写单次回复约800字",
            "单次回复不要写约800字",
            "单次回复约800字以内",
            "单次回复不超过800字",
            "单次回复控制在800字",
            "单次回复约800-1000字",
            "单次回复约800至1000字",
            "单次回复约800字到1000字",
            "单次回复约800字，最多1000字",
            "例如：单次回复约800字",
            "如果是长篇，单次回复约800字",
            "Example dialogue: 用户说‘正文约800字’",
        )) {
            assertNull(policy, ReplyLengthPolicy.cardGuidance(policy, null))
        }
    }

    @Test
    fun conflictingLengthsAndModesAreNotSilentlyOverridden() {
        assertNull(ReplyLengthPolicy.cardGuidance("单次回复约800字", "正文约300字"))
        assertNull(ReplyLengthPolicy.cardGuidance("单次回复约800字", "正文至少800字"))
        assertNull(ReplyLengthPolicy.cardGuidance("单次回复约800字", "正文不超过1000字"))
    }

    @Test
    fun formattedExamplesAndScenarioCannotBecomeSystemLengthPolicies() {
        val result = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "应用规则",
                stableCharacterDefinition = "Scenario: 作文至少800字。\nExample dialogue:\n正文约800字。",
                latestUserMessage = "继续",
            ),
        )
        assertFalse(result.first().content.contains("角色卡默认篇幅目标"))
        assertFalse(result.last().content.contains("角色卡默认篇幅目标"))
    }

    @Test
    fun explicitCardPolicyStaysInTheTailReminderWithoutMovingDynamicData() {
        val result = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "应用规则",
                stableCharacterDefinition = "角色设定。",
                cardSystemInstructions = "单次回复约800字。",
                postHistoryInstructions = "保持角色口吻。",
                activeMemoryFacts = listOf("PRIVATE_DYNAMIC_MEMORY"),
                latestUserMessage = "这次只回答一句。",
            ),
        )
        assertTrue(result.last().content.contains("保持角色口吻。"))
        assertFalse(result.first().content.contains("保持角色口吻。"))
        assertTrue(result.first().content.contains("post-history writing preferences"))
        assertTrue(result.first().content.contains("亲密或高羁绊不等于失去个性"))
        assertFalse(result.first().content.contains("正文约800字"))
        assertTrue(result.first().content.contains("当前用户明确要求"))
        assertFalse(result.first().content.contains("PRIVATE_DYNAMIC_MEMORY"))
        assertTrue(result.last().content.contains("这次只回答一句。"))
        assertTrue(result.last().content.contains("正文约800字"))
        assertTrue(result.last().content.contains("优先满足当前请求"))
    }

    @Test
    fun theSystemPrefixCarriesNoCharacterCountAtAll() {
        val result = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "应用规则",
                stableCharacterDefinition = "角色设定。",
                cardSystemInstructions = "单次回复约800字。",
                latestUserMessage = "继续",
            ),
        )
        val system = result.first().content
        assertFalse(system.contains("800"))
        assertFalse(system.contains("角色卡默认篇幅目标"))
        assertEquals(1, Regex("800").findAll(result.last().content).count())
    }

    @Test
    fun aCardThatNamesItsOwnLengthInPostHistoryIsNeverRestatedAtTheTail() {
        // Post-history instructions are already the tail. Appending the normalised target there
        // would put two statements of the same number one line apart, so the card clause stands
        // alone and the whole prompt carries exactly one statement of it.
        val result = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "应用规则",
                stableCharacterDefinition = "角色设定。",
                postHistoryInstructions = "单次回复不少于 800 个中文字符。",
                latestUserMessage = "继续",
            ),
        )
        assertFalse(result.first().content.contains("800"))
        assertTrue(result.last().content.contains("单次回复不少于 800 个中文字符。"))
        assertFalse(result.last().content.contains("角色卡默认篇幅目标"))
        assertEquals(1, Regex("800").findAll(result.first().content + result.last().content).count())
    }

    @Test
    fun aLengthThatOnlyLivesInTheSystemPrefixStillGetsATailReminder() {
        // Mirrors ChatSendMessageCoordinator: the card's systemPrompt is formatted into the stable
        // definition and is also passed as cardSystemInstructions. Here the card text sits in the
        // cached prefix, far from the end of the conversation, so the trailing reminder is a genuine
        // reminder rather than adjacent repetition.
        val result = PromptAssembler.assemble(
            PromptAssemblyInput(
                stableSystemPrefix = "应用规则",
                stableCharacterDefinition = "角色设定。\n\nSystem instructions: 单次回复约800字。",
                cardSystemInstructions = "单次回复约800字。",
                postHistoryInstructions = "保持角色口吻。",
                latestUserMessage = "继续",
            ),
        )
        assertTrue(result.first().content.contains("单次回复约800字。"))
        assertTrue(result.last().content.contains("角色卡默认篇幅目标：正文约800字"))
        assertEquals(1, Regex("角色卡默认篇幅目标").findAll(result.last().content).count())
    }
}
