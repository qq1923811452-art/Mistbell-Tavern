package com.mistbell.tavern.v3.core.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalIntentGateTest {
    @Test
    fun rejectsBlankPunctuationAndEmojiOnlyMessages() {
        assertFalse(RetrievalIntentGate.shouldTriggerRetrieval(""))
        assertFalse(RetrievalIntentGate.shouldTriggerRetrieval("   "))
        assertFalse(RetrievalIntentGate.shouldTriggerRetrieval("！！！"))
        assertFalse(RetrievalIntentGate.shouldTriggerRetrieval("😀😀"))
    }

    @Test
    fun rejectsPureGreetingsAndFillers() {
        listOf("早安", "嗯嗯", "好的", "继续", "hi", "hello", "ok", "哈哈").forEach { message ->
            assertFalse(message, RetrievalIntentGate.shouldTriggerRetrieval(message))
        }
    }

    @Test
    fun allowsQuestionsEntitiesAndNormalShortSentences() {
        assertTrue(RetrievalIntentGate.shouldTriggerRetrieval("你记得我的剑吗？"))
        assertTrue(RetrievalIntentGate.shouldTriggerRetrieval("北京旅行"))
        assertTrue(RetrievalIntentGate.shouldTriggerRetrieval("我的佩剑"))
        assertTrue(RetrievalIntentGate.shouldTriggerRetrieval("concise answers"))
        assertTrue(RetrievalIntentGate.shouldTriggerRetrieval("早安露娜", setOf("露娜")))
    }
}
