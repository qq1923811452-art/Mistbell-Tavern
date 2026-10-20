package com.mistbell.tavern.v3.feature.chat

/** Selects the newest contiguous whole history messages within the configured limits. */
object PromptHistoryBudget {
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
        return selectedNewestFirst.asReversed()
    }
}
