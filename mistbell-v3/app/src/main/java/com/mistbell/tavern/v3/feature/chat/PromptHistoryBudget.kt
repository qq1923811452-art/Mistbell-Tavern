package com.mistbell.tavern.v3.feature.chat

/** Selects the newest contiguous whole history messages within the configured limits. */
object PromptHistoryBudget {
    /**
     * Align the window start to fixed message blocks. A sliding window shifts the prompt prefix
     * every turn, which defeats provider prefix caches; block alignment changes it only once per
     * block. Slightly exceeding the character budget is cheaper than losing the whole cache.
     */
    const val WINDOW_BLOCK_MESSAGES = 10

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
        return if (alignedStart < start) history.subList(alignedStart, history.size) else selected
    }
}
