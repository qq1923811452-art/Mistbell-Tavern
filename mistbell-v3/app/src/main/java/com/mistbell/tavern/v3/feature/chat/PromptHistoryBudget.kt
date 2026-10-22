package com.mistbell.tavern.v3.feature.chat

/** Selects the newest contiguous whole history messages within the configured limits. */
object PromptHistoryBudget {
    /**
     * Align the window start to fixed message blocks. A sliding window shifts the prompt prefix
     * every turn, which defeats provider prefix caches; block alignment changes it only once per
     * block. Slightly exceeding the character budget is cheaper than losing the whole cache.
     */
    const val WINDOW_BLOCK_MESSAGES = 10

    /** Extra characters allowed when the aligned window expands past the budget. */
    const val ALIGNMENT_SLACK_CHARS = 2_000

    fun select(history: List<PromptMessage>, maxCharacters: Int, maxMessages: Int): List<PromptMessage> {
        require(maxCharacters >= 0) { "maxCharacters must be non-negative." }
        require(maxMessages >= 0) { "maxMessages must be non-negative." }
        if (maxMessages == 0 || history.isEmpty()) return emptyList()
        if (maxCharacters == 0) return emptyList()

        val selectedNewestFirst = mutableListOf<PromptMessage>()
        var usedCharacters = 0
        for (message in history.asReversed()) {
            if (selectedNewestFirst.size >= maxMessages) break
            val size = message.content.length
            if (usedCharacters + size > maxCharacters) {
                // Never truncate a message, especially a character's long-form reply. If the
                // newest message alone exceeds the budget, keep it whole; otherwise stop before
                // the first message that would break the contiguous newest-history window.
                if (selectedNewestFirst.isEmpty()) selectedNewestFirst += message
                break
            }
            selectedNewestFirst += message
            usedCharacters += size
        }
        val selected = selectedNewestFirst.asReversed()
        val start = history.size - selected.size
        if (start <= 0) return selected
        if (start < WINDOW_BLOCK_MESSAGES) return selected
        val alignedStart = start / WINDOW_BLOCK_MESSAGES * WINDOW_BLOCK_MESSAGES
        if (alignedStart >= start) return selected
        val expanded = history.subList(alignedStart, history.size)
        val expandedChars = expanded.sumOf { it.content.length }
        return if (expandedChars <= maxCharacters + ALIGNMENT_SLACK_CHARS) expanded else selected
    }
}
