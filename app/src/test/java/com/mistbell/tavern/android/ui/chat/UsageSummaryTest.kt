package com.mistbell.tavern.android.ui.chat

import com.mistbell.tavern.android.data.api.Usage
import com.mistbell.tavern.android.data.api.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话级/单条 usage 聚合纯函数单测（JVM）。
 *
 * 铁律「缺失数据不得填 0」的回归防线：
 * - 无 usage 的消息整条跳过（不进 messageCount、不累加零值）；
 * - 缓存命中率只在「命中 + 未命中」两字段有真实数据（和 > 0）时才算得出，否则为 null（UI 显示「未知」）；
 * - 全未命中（真值 0 命中）应算得出 0%，而不是「未知」。
 */
class UsageSummaryTest {

    private fun assistantMessage(
        id: String,
        usage: Usage?,
    ) = Message(id = id, role = "assistant", content = "回复", usage = usage)

    @Test
    fun `无 usage 的消息整条跳过 不计入 messageCount 也不累加`() {
        val summary = summarizeUsage(listOf(assistantMessage("a", null), assistantMessage("b", null)))
        assertEquals(0, summary.messageCount)
        assertEquals(0, summary.totalTokens)
        assertNull(summary.cacheHitRate)
    }

    @Test
    fun `只累加携带真实 usage 的消息`() {
        val summary =
            summarizeUsage(
                listOf(
                    assistantMessage(
                        "a",
                        Usage(
                            promptTokens = 100,
                            completionTokens = 20,
                            totalTokens = 120,
                            cacheHitTokens = 90,
                            cacheMissTokens = 30,
                        ),
                    ),
                    assistantMessage("b", null),
                    assistantMessage(
                        "c",
                        Usage(
                            promptTokens = 200,
                            completionTokens = 30,
                            totalTokens = 230,
                            cacheHitTokens = 0,
                            cacheMissTokens = 200,
                        ),
                    ),
                ),
            )
        assertEquals(2, summary.messageCount)
        assertEquals(300, summary.promptTokens)
        assertEquals(50, summary.completionTokens)
        assertEquals(350, summary.totalTokens)
        assertEquals(90, summary.cacheHitTokens)
        assertEquals(230, summary.cacheMissTokens)
        // (90 + 0) / ((90 + 30) + (0 + 200)) = 90 / 320
        assertEquals(90.0 / 320.0, summary.cacheHitRate!!, 1e-9)
    }

    @Test
    fun `缓存字段全 0 时命中率为未知 不拿 0 冒充`() {
        val summary =
            summarizeUsage(
                listOf(
                    assistantMessage(
                        "a",
                        Usage(promptTokens = 100, completionTokens = 20, totalTokens = 120),
                    ),
                ),
            )
        assertEquals(1, summary.messageCount)
        assertEquals(120, summary.totalTokens)
        assertNull(summary.cacheHitRate)
    }

    @Test
    fun `单条命中率只认真实缓存字段`() {
        assertNull(Usage(promptTokens = 100, completionTokens = 10, totalTokens = 110).cacheHitRateOrNull())
        assertEquals(
            0.75,
            Usage(cacheHitTokens = 75, cacheMissTokens = 25).cacheHitRateOrNull()!!,
            1e-9,
        )
        // 全未命中是真实数据（缓存确实没命中），应算得出 0%
        assertEquals(
            0.0,
            Usage(cacheHitTokens = 0, cacheMissTokens = 40).cacheHitRateOrNull()!!,
            1e-9,
        )
    }

    @Test
    fun `EMPTY 汇总无数据且命中率未知`() {
        assertEquals(0, UsageSummary.EMPTY.messageCount)
        assertEquals(0, UsageSummary.EMPTY.totalTokens)
        assertNull(UsageSummary.EMPTY.cacheHitRate)
    }
}
