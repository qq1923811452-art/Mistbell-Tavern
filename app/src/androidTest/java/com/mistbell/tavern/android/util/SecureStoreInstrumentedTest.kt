package com.mistbell.tavern.android.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStoreInstrumentedTest {
    @Test
    fun apiKeyRoundTripsWithoutPersistingPlaintext() {
        val apiKey = "test-api-key-do-not-log"
        val encrypted = SecureStore.wrap(apiKey)

        assertTrue(SecureStore.isEncrypted(encrypted))
        assertNotEquals(apiKey, encrypted)
        assertEquals(apiKey, SecureStore.unwrap(encrypted))
    }

    @Test
    fun legacyPlaintextValuesRemainReadableForMigration() {
        val legacy = "legacy-api-key"

        assertEquals(legacy, SecureStore.unwrap(legacy))
    }

    @Test
    fun literalEncryptionPrefixInAnApiKeyDoesNotBypassEncryption() {
        val apiKey = "enc:v1:not-an-encrypted-payload"
        val encrypted = SecureStore.wrap(apiKey)

        assertTrue(SecureStore.isEncrypted(encrypted))
        assertNotEquals(apiKey, encrypted)
        assertEquals(apiKey, SecureStore.unwrap(encrypted))
    }

    @Test
    fun malformedEncryptedPayloadIsNotAcceptedAsValidCiphertext() {
        assertTrue(!SecureStore.isEncrypted("enc:v1:not-base64"))
        assertEquals("", SecureStore.unwrap("enc:v1:not-base64"))
    }

    @Test
    fun backupCannotRestoreCredentialKeyAsPlaintextWhenSensitiveFlagIsFalse() {
        val entry =
            com.mistbell.tavern.android.data.repository.BackupManager.SettingsBackupEntry(
                key = "llm_api_key",
                value = "backup-api-key",
                sensitive = false,
            )

        val restored = com.mistbell.tavern.android.data.repository.BackupManager.toSettingsEntity(entry)

        assertTrue(SecureStore.isEncrypted(restored.value))
        assertEquals("backup-api-key", SecureStore.unwrap(restored.value))
    }
}
