package com.mistbell.tavern.v3.feature.era.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EraCommandParserTest {
    private fun line(
        id: Long,
        text: String,
    ) = EraLine(id = id, text = text)

    @Test
    fun collectsTrailingNumberedOptions() {
        val lines =
            listOf(
                line(1, "幻想乡测试"),
                line(2, "[0] 开始"),
                line(3, "[1] 退出"),
            )

        val commands = EraCommandParser.tailCommands(lines)

        assertEquals(listOf("0", "1"), commands.map { it.value })
        assertEquals(listOf("[0] 开始", "[1] 退出"), commands.map { it.text })
    }

    @Test
    fun stopsAtFirstNonOptionLineAboveTheBlock() {
        val lines =
            listOf(
                line(1, "[7] 旧选项"),
                line(2, "普通正文"),
                line(3, "[0] 挨拶"),
                line(4, "[1] 移動"),
            )

        val commands = EraCommandParser.tailCommands(lines)

        assertEquals(listOf("0", "1"), commands.map { it.value })
    }

    @Test
    fun ignoresBlankTailAndDuplicates() {
        val lines =
            listOf(
                line(1, "[0] 開始"),
                line(2, " "),
                line(3, ""),
                line(4, "[0] 開始"),
            )

        val commands = EraCommandParser.tailCommands(lines)

        assertEquals(1, commands.size)
        assertEquals("0", commands.single().value)
    }

    @Test
    fun returnsEmptyWhenTailIsPlainText() {
        val lines = listOf(line(1, "只是普通叙述"), line(2, "没有选项"))

        assertTrue(EraCommandParser.tailCommands(lines).isEmpty())
    }
}
