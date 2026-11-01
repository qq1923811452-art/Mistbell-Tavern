package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationScrollPolicyTest {
    @Test
    fun historyWaitsForReadinessThenFollowsGrowth() {
        val policy = ConversationScrollPolicy()
        assertFalse(policy.onHistory(ready = false, userMessageId = "old"))
        assertTrue(policy.onHistory(ready = true, userMessageId = "old"))
        assertTrue(policy.onHistory(ready = true, userMessageId = "old"))
    }

    @Test
    fun userScrollStopsQueuedGrowthUntilExplicitReturn() {
        val policy = ConversationScrollPolicy()
        policy.onHistory(ready = true, userMessageId = "old")
        policy.onUserScroll()
        assertFalse(policy.followsBottom)
        assertFalse(policy.onHistory(ready = true, userMessageId = "old"))
        policy.returnToBottom()
        assertTrue(policy.onHistory(ready = true, userMessageId = "old"))
    }

    @Test
    fun visibleLastItemIsNotEnoughWhenItCanStillScrollForward() {
        val policy = ConversationScrollPolicy()
        policy.onUserScroll()
        policy.onReachedBottom(canScrollForward = true)
        assertFalse(policy.followsBottom)
        policy.onReachedBottom(canScrollForward = false)
        assertTrue(policy.followsBottom)
    }

    @Test
    fun newUserMessageRestoresFollowingButAssistantGrowthDoesNot() {
        val policy = ConversationScrollPolicy()
        policy.onHistory(ready = true, userMessageId = "old")
        policy.onUserScroll()
        assertFalse(policy.onHistory(ready = true, userMessageId = "old"))
        assertTrue(policy.onHistory(ready = true, userMessageId = "sent"))
    }

    @Test
    fun switchingScopeCannotReusePreviousIntentOrUserMessage() {
        val previous = ConversationScrollPolicy()
        previous.onHistory(ready = true, userMessageId = "old")
        previous.onUserScroll()
        val next = ConversationScrollPolicy()
        assertFalse(next.onHistory(ready = false, userMessageId = "old"))
        assertTrue(next.onHistory(ready = true, userMessageId = null))
        assertFalse(previous.followsBottom)
    }
}
