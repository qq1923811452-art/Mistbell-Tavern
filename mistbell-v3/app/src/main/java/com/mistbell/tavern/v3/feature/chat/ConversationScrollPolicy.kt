package com.mistbell.tavern.v3.feature.chat

/** Scope-local intent: layout growth must not be mistaken for a user leaving the bottom. */
internal class ConversationScrollPolicy {
    var followsBottom: Boolean = true
        private set
    private var historyLoaded = false
    private var lastUserMessageId: String? = null

    fun onHistory(ready: Boolean, userMessageId: String?): Boolean {
        if (!ready) return false
        val sentMessage = historyLoaded && userMessageId != null && userMessageId != lastUserMessageId
        lastUserMessageId = userMessageId
        historyLoaded = true
        if (sentMessage) followsBottom = true
        return followsBottom
    }

    fun onUserScroll() {
        followsBottom = false
    }

    fun onReachedBottom(canScrollForward: Boolean) {
        if (!canScrollForward) followsBottom = true
    }

    fun returnToBottom() {
        followsBottom = true
    }
}
