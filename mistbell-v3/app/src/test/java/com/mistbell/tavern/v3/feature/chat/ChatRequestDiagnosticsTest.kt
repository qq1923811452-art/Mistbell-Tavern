package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRequestDiagnosticsTest {
    @Test
    fun modelLabelIsBoundedAndDropsFormattingCharacters() {
        assertEquals("deepseek-flash", ChatRequestDiagnostics.modelLabel(" deepseek-flash\n"))
        assertEquals("unknown", ChatRequestDiagnostics.modelLabel("   "))
        assertEquals(64, ChatRequestDiagnostics.modelLabel("m".repeat(200)).length)
        assertEquals("model_1.0-v3", ChatRequestDiagnostics.modelLabel("model_1.0-v3"))
    }

    @Test
    fun endpointHostNeverIncludesPathOrCredentials() {
        assertEquals(
            "api.deepseek.com",
            ChatRequestDiagnostics.endpointHost("https://user:secret@api.deepseek.com/v1?key=abc"),
        )
        assertEquals("unknown", ChatRequestDiagnostics.endpointHost("not a url"))
        assertEquals("unknown", ChatRequestDiagnostics.endpointHost(""))
    }
}
