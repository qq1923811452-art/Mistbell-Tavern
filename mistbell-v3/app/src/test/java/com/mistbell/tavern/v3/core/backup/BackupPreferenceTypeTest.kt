package com.mistbell.tavern.v3.core.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceTypeTest {
    @Test
    fun encodingAndDecodingRoundTripsEverySupportedPreferenceType() {
        val preferences =
            mapOf<String, Any>(
                "chat_context_tokens" to 32_000,
                "chat_max_tokens" to 4096,
                "enabled" to true,
                "ratio" to 0.625f,
                "large_counter" to 9_000_000_000L,
                "title" to "hello",
                "selected_models" to setOf("alpha", "beta"),
            )

        val encoded = BackupPreferenceCodec.encode(preferences)
        val restored = BackupPreferenceCodec.decode(JSONObject(encoded.toString()))

        assertEquals(preferences, restored)
        assertEquals("int", encoded.getJSONObject(BackupSource.SETTING_TYPES_KEY).getString("chat_context_tokens"))
    }

    @Test
    fun credentialFilterExcludesTokenVariantsButPreservesTokenBudgets() {
        val credentialKeys =
            listOf(
                "token", "session_token", "id_token", "API_TOKEN", "accessToken",
                "chatApiToken", "memory_api_token", "clientSecret", "private_key", "authBearer",
            )
        credentialKeys.forEach { key -> assertTrue("$key should be filtered", isBackupCredentialKey(key)) }
        assertFalse(isBackupCredentialKey("chat_context_tokens"))
        assertFalse(isBackupCredentialKey("chat_max_tokens"))
        val encoded =
            BackupPreferenceCodec.encode(
                mapOf("access_token" to "secret", "session_token" to "secret", "chat_max_tokens" to 4096),
            )
        assertFalse(encoded.has("access_token"))
        assertFalse(encoded.has("session_token"))
        assertEquals(4096, encoded.getInt("chat_max_tokens"))
    }

    @Test
    fun malformedOrOverflowingMetadataIsRejectedBeforeItCanBeApplied() {
        val overflow =
            JSONObject()
                .put("counter", 2_147_483_648L)
                .put(BackupSource.SETTING_TYPES_KEY, JSONObject().put("counter", "int"))
        assertThrows(ArithmeticException::class.java) { BackupPreferenceCodec.decode(overflow) }

        val malformed =
            JSONObject()
                .put("enabled", "yes")
                .put(BackupSource.SETTING_TYPES_KEY, JSONObject().put("enabled", "boolean"))
        assertThrows(IllegalArgumentException::class.java) { BackupPreferenceCodec.decode(malformed) }

        val missingType =
            JSONObject()
                .put("counter", 1)
                .put(BackupSource.SETTING_TYPES_KEY, JSONObject().put("counter", "int").put("missing", "int"))
        assertThrows(IllegalArgumentException::class.java) { BackupPreferenceCodec.decode(missingType) }
    }
}
