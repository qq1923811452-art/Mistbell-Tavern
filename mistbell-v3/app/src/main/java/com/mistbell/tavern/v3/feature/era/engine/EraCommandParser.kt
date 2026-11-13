package com.mistbell.tavern.v3.feature.era.engine

/**
 * era 剧本的选项通常以 `PRINTBUTTON "[0] 挨拶", 0` 的形式打印成普通文本。
 *
 * 引擎正常时会把它们放进 `input.request.buttons`；但最后一行若在等待输入前还没落到
 * 显示行里，`buttons` 会是空的（实测 headless 宿主复现）。这时前端从文本尾部自己认出
 * `[n] ...` 的选项，保证玩家永远点得动。
 */
internal object EraCommandParser {
    private val OPTION = Regex("""^\[(\d+)]\s*(.+)$""")

    /** 从尾部往上收集连续出现的 `[n] 文本` 行，返回按出现顺序的候选选项。 */
    fun tailCommands(
        lines: List<EraLine>,
        limit: Int = MAX_SCAN_LINES,
    ): List<EwpButton> {
        val found = LinkedHashMap<Int, String>()
        var scanned = 0
        var closed = false
        for (line in lines.asReversed()) {
            if (closed || scanned >= limit) break
            scanned++
            closed = absorb(line, found)
        }
        return found.entries
            .sortedBy { it.key }
            .take(MAX_BUTTONS)
            .mapIndexed { position, entry ->
                EwpButton(index = position, text = entry.value, value = entry.key.toString())
            }
    }

    /** 吸收一行；返回 true 表示选项块已经结束，可以停止向上扫描。 */
    private fun absorb(
        line: EraLine,
        found: MutableMap<Int, String>,
    ): Boolean {
        val text = line.text.trim()
        val match = if (text.isEmpty()) null else OPTION.matchEntire(text)
        val number = match?.groupValues?.get(1)?.toIntOrNull()
        if (match != null && number != null) {
            found.putIfAbsent(number, text)
        }
        return match == null && text.isNotEmpty() && found.isNotEmpty()
    }

    private const val MAX_SCAN_LINES = 40
    private const val MAX_BUTTONS = 12
}
