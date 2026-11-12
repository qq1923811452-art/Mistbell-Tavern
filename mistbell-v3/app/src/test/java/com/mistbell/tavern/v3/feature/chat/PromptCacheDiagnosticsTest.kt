package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PromptCacheDiagnosticsTest {
    @Test
    fun tailChangesDoNotChangeStablePrefixHashOrLeakContent() {
        val messages =
            listOf(
                PromptMessage(PromptRole.SYSTEM, "private rules"),
                PromptMessage(PromptRole.USER, "private opening"),
                PromptMessage(PromptRole.USER, "tail"),
            )
        val first = PromptCacheDiagnostics.describe(messages, 1)
        val second =
            PromptCacheDiagnostics.describe(
                messages.dropLast(1) + PromptMessage(PromptRole.USER, "changed"),
                1,
            )
        assertEquals(first, second)
        assertFalse(first.contains("private"))
    }

    @Test
    fun systemOrPinnedRoleChangesAreVisible() {
        val messages =
            listOf(
                PromptMessage(PromptRole.SYSTEM, "rules"),
                PromptMessage(PromptRole.USER, "opening"),
            )
        val original = PromptCacheDiagnostics.describe(messages, 1)
        assertNotEquals(original, PromptCacheDiagnostics.describe(messages.reversed(), 1))
        assertNotEquals(
            original,
            PromptCacheDiagnostics.describe(messages.take(1) + PromptMessage(PromptRole.ASSISTANT, "opening"), 1),
        )
    }
}
