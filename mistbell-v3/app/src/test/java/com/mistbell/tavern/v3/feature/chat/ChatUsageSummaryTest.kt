package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUsageSummaryTest {
    @Test
    fun absentReasoningKeepsExistingChineseSummaryAndIntegerCacheRatio() {
        assertEquals(
            "Token 用量：输入 30 · 缓存 10（33%） · 输出 12 · 合计 42",
            formatUsageSummary(ChatTokenUsage(30, 12, 42, cachedPromptTokens = 10)),
        )
    }

    @Test
    fun positiveReasoningIsShownWithoutAddingItToCompletionOrTotal() {
        assertEquals(
            "Token 用量：输入 30 · 缓存 未知 · 输出 12 · 合计 42 · 推理 8 token",
            formatUsageSummary(ChatTokenUsage(30, 12, 42, reasoningTokens = 8)),
        )
    }

    @Test
    fun explicitlyReportedZeroReasoningIsShown() {
        assertEquals(
            "Token 用量：输入 10 · 缓存 0（0%） · 输出 2 · 合计 12 · 推理 0 token",
            formatUsageSummary(ChatTokenUsage(10, 2, 12, cachedPromptTokens = 0, reasoningTokens = 0)),
        )
    }

    @Test
    fun zeroPromptOmitsCacheRatioWithoutDividingByZero() {
        assertEquals(
            "Token 用量：输入 0 · 缓存 0 · 输出 2 · 合计 2",
            formatUsageSummary(ChatTokenUsage(0, 2, 2, cachedPromptTokens = 0)),
        )
    }

    @Test
    fun absentCacheAndReasoningRemainUnknownAndOmittedRespectively() {
        assertEquals(
            "Token 用量：输入 0 · 缓存 未知 · 输出 0 · 合计 0",
            formatUsageSummary(ChatTokenUsage(0, 0, 0)),
        )
    }
}
