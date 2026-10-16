package com.mistbell.tavern.v3.core.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogStoreTest {
    @Test
    fun redactsCredentialsAndExportsReadableLog() {
        val directory = kotlin.io.path.createTempDirectory(prefix = "mistbell-diagnostics-test").toFile()
        try {
            val store = DiagnosticLogStore(directory) { Instant.parse("2026-10-16T12:00:00Z") }
            store.info("embedding.initialize", "Bearer super-secret-token; Bearer 私密令牌; api_key=private-key")
            store.warn(
                "network.error",
                "Authorization: Basic dXNlcjpwYXNz; X-Api-Key=api-secret; https://example.test/?token=query-secret; hf_abcdefghijklmnopqrstuvwxyz; \"token\": \"json-secret\"",
            )
            store.warn(
                "provider.raw_token",
                "sk-12345678901234567890 ghp_123456789012345678901234567890 github_pat_123456789012345678901234567890 xoxb-1234567890-abcdefghij AKIA1234567890123456 ya29.123456789012345678901234567890",
            )
            val output = ByteArrayOutputStream()
            store.exportTo(output)
            val exported = output.toString(Charsets.UTF_8.name())

            assertTrue(exported.contains("embedding.initialize"))
            assertTrue(exported.contains("[REDACTED]"))
            assertFalse(exported.contains("super-secret-token"))
            assertFalse(exported.contains("私密令牌"))
            assertFalse(exported.contains("private-key"))
            assertFalse(exported.contains("dXNlcjpwYXNz"))
            assertFalse(exported.contains("api-secret"))
            assertFalse(exported.contains("query-secret"))
            assertFalse(exported.contains("hf_abcdefghijklmnopqrstuvwxyz"))
            assertFalse(exported.contains("json-secret"))
            assertFalse(exported.contains("sk-12345678901234567890"))
            assertFalse(exported.contains("ghp_123456789012345678901234567890"))
            assertFalse(exported.contains("github_pat_123456789012345678901234567890"))
            assertFalse(exported.contains("xoxb-1234567890-abcdefghij"))
            assertFalse(exported.contains("AKIA1234567890123456"))
            assertFalse(exported.contains("ya29.123456789012345678901234567890"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun stripsLineBreaksAndBoundsEventDetails() {
        val directory = kotlin.io.path.createTempDirectory(prefix = "mistbell-diagnostics-test").toFile()
        try {
            val store = DiagnosticLogStore(directory) { Instant.EPOCH }
            store.warn("unsafe\nevent", "detail\r\nline")
            val text = File(directory, DiagnosticLogStore.LOG_FILE_NAME).readText()

            assertTrue(text.contains("unsafe event - detail line"))
            assertFalse(text.contains("unsafe\nevent"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
