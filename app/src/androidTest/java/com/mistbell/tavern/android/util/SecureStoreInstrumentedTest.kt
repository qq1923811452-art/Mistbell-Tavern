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
}
