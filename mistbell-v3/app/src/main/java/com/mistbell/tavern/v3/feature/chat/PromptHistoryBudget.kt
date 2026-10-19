package com.mistbell.tavern.v3.feature.chat

/** Selects the newest whole history messages that fit both configured limits. */
object PromptHistoryBudget {
    fun select(history: List<PromptMessage>, maxCharacters: Int, maxMessages: Int): List<PromptMessage> {
        require(maxCharacters >= 0) { "maxCharacters must be non-negative." }
        require(maxMessages >= 0) { "maxMessages must be non-negative." }
        if (maxCharacters == 0 || maxMessages == 0) return emptyList()
        val selectedNewestFirst = mutableListOf<PromptMessage>()
        var usedCharacters = 0
        for (message in history.asReversed()) {
            if (selectedNewestFirst.size >= maxMessages) break
            val size = message.content.length
            if (usedCharacters + size > maxCharacters) continue
            selectedNewestFirst += message
            usedCharacters += size
        }
        return selectedNewestFirst.asReversed()
    }
}
