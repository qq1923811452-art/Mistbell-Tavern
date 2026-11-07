package com.mistbell.tavern.v3.feature.chat

/**
 * Reads only explicit card instruction fields; descriptive text and examples are not policies.
 * [tailGuidance] omits a derived target already stated in post-history instructions. Original card
 * text is preserved, including any repetitions across card fields.
 */
internal object ReplyLengthPolicy {
    private const val CONTEXT = "(?:单次回复|每次回复|每轮回复|回复篇幅|正文篇幅|回复正文|正文)"
    private val contextualClause = Regex(CONTEXT)
    private const val UNITS = "(?:中文字符|中文字|汉字|字符|字)"
    private const val TARGET_UNIT_GROUP = 3
    private data class Target(val mode: String, val count: Int, val chineseOnly: Boolean)
    private val target = Regex(
        "^(?:[0-9]+[.、]\\s*)?(?:叙事篇幅与细节密度[：:]\\s*)?" +
            "(?:请)?\\s*$CONTEXT(?:的)?(?:篇幅|长度|字数)?\\s*" +
            "(?:(?:应当|应该|应|必须|须|需要|需|请|保持|以|为|目标为|目标是)\\s*)*" +
            "[：:]?\\s*(约|大约|不少于|至少|达到)?\\s*([0-9]{2,5})\\s*(?:个)?($UNITS)",
    )
    private val ambiguous = Regex(
        "不超过|至多|最多|以内|以下|(?<!不)少于|不多于|小于|低于|" +
            "[0-9]\\s*(?:[-~～—–至到]|字\\s*(?:至|到))\\s*[0-9]",
    )
    private val characterCount = Regex("[0-9]+\\s*(?:个)?$UNITS")

    // Guard exits intentionally reject ambiguous policies before any guidance reaches the prompt.
    @Suppress("ReturnCount")
    fun cardGuidance(systemInstructions: String?, postHistory: String?): String? {
        val clauses = listOfNotNull(systemInstructions, postHistory)
            .flatMap { it.split(Regex("[。！？!?；;\\n\\r]")) }
            .map(String::trim)
            .filter { contextualClause.containsMatchIn(it) && Regex("[0-9]").containsMatchIn(it) }
        if (clauses.isEmpty()) return null
        val targets = mutableListOf<Target>()
        for (clause in clauses) {
            // Do not convert negation, upper bounds, ranges, or competing limits into a default.
            if (characterCount.findAll(clause).count() != 1) return null
            val match = target.find(clause) ?: return null
            // Later paragraph counts are not length ranges; unrelated prohibitions are style rules.
            val trailingLength = clause.substring(match.range.last + 1)
                .substringBefore('：').substringBefore(':').substringBefore('，').substringBefore(',')
            if (ambiguous.containsMatchIn(trailingLength)) return null
            val count = match.groupValues[2].toIntOrNull() ?: return null
            val mode = when (match.groupValues[1]) {
                "不少于", "至少", "达到" -> "至少"
                "约", "大约" -> "约"
                else -> ""
            }
            val chineseOnly = match.groupValues[TARGET_UNIT_GROUP] in setOf("中文字符", "中文字", "汉字")
            targets += Target(mode, count, chineseOnly)
        }
        val (mode, count, chineseOnly) = targets.distinct().singleOrNull() ?: return null
        val length = if (chineseOnly) {
            "${count}个中文字符（仅统计正文汉字，不以标点、空格、拉丁字母或标签补足字数）"
        } else {
            "${count}字（可见正文字符，不含标签或字数说明）"
        }
        return "角色卡默认篇幅目标：正文${mode}${length}。" +
            "当前用户没有另行指定篇幅时，请以此为目标充分完成叙事，" +
            "不要仅凭几句对白就结束；在正常结束前检查正文是否明显不足。" +
            "用户明确要求简短、指定不同字数或非叙事回答时，优先满足当前请求。" +
            "不要展示检查过程，不得为达标重复内容或替用户安排言行。"
    }

    /**
     * What may be appended after the latest user message.
     *
     * Post-history instructions already occupy the tail, so do not restate their numeric target.
     * A target supplied only by system instructions retains its tail reminder. Resolve the combined
     * policy first so conflicts, ambiguous units and unsupported clauses still suppress guidance.
     * Shared counting rules remain in [PromptAssembler.RESPONSE_COMPLETENESS_RULE].
     */
    fun tailGuidance(systemInstructions: String?, postHistory: String?): String? =
        cardGuidance(systemInstructions, postHistory)
            ?.takeIf { cardGuidance(null, postHistory) == null }
}
