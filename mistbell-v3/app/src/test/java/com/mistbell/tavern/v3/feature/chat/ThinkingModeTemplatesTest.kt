package com.mistbell.tavern.v3.feature.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingModeTemplatesTest {
    @Test
    fun deepSeekUsesTheOfficialThinkingType() {
        val disabled = JSONObject(ThinkingModeTemplates.disabled("https://api.deepseek.com", "deepseek-flash")!!)
        assertEquals("disabled", disabled.getJSONObject("thinking").getString("type"))
        val enabled = JSONObject(ThinkingModeTemplates.enabled("https://api.deepseek.com", "deepseek-flash")!!)
        assertEquals("enabled", enabled.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun deepSeekModelThroughAGatewayStillUsesThinkingType() {
        val disabled = JSONObject(ThinkingModeTemplates.disabled("https://api.odys.work/v1", "deepseek-flash")!!)
        assertEquals("disabled", disabled.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun dashScopeUsesEnableThinking() {
        val disabled = JSONObject(ThinkingModeTemplates.disabled("https://dashscope.aliyuncs.com", "qwen3")!!)
        assertEquals(false, disabled.getBoolean("enable_thinking"))
    }

    @Test
    fun unknownAndVersionedProvidersDoNotGuess() {
        assertNull(ThinkingModeTemplates.disabled("https://api.example.com", "some-model"))
        assertNull(ThinkingModeTemplates.disabled("https://api.anthropic.com", "claude-4.7"))
        assertNull(ThinkingModeTemplates.disabled("https://generativelanguage.googleapis.com", "gemini-3"))
    }
}
