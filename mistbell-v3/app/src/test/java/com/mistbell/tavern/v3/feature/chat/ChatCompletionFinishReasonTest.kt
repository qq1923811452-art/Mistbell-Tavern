package com.mistbell.tavern.v3.feature.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionFinishReasonTest {
    @Test
    fun respectsOpenAiFinishReasonAndNeverInventsStopWhenAbsent() {
        val reasons = mapOf(
            "stop" to ChatFinishReason.STOP,
            "end_turn" to ChatFinishReason.STOP,
            "length" to ChatFinishReason.LENGTH,
            "insufficient_system_resource" to ChatFinishReason.OTHER,
            "content_filter" to ChatFinishReason.OTHER,
        )
        for ((raw, expected) in reasons) {
            val root = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", raw)))
            assertEquals(expected, classifyCompletionFinishReason(completionFinishReason(root)))
        }
        assertEquals(ChatFinishReason.OTHER, classifyCompletionFinishReason(null))
        assertEquals(null, completionFinishReason(JSONObject("""{"choices":[{"finish_reason":null}]}""")))
        assertEquals(null, completionFinishReason(JSONObject("""{"choices":[{}]}""")))
    }

    @Test
    fun respectsNativeGeminiFinishReason() {
        for ((raw, expected) in mapOf("STOP" to ChatFinishReason.STOP, "MAX_TOKENS" to ChatFinishReason.LENGTH)) {
            val root = JSONObject("""{"candidates":[{"finishReason":"$raw"}]}""")
            assertEquals(expected, classifyCompletionFinishReason(completionFinishReason(root)))
        }
    }
}
