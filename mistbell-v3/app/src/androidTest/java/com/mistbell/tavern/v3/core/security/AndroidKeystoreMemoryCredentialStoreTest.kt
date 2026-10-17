package com.mistbell.tavern.v3.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreMemoryCredentialStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun storesOnlyCiphertextAndResolvesOriginalSecret() {
        val store = AndroidKeystoreMemoryCredentialStore(context)
        val secret = "instrumentation-test-secret"
        val handle = store.put(secret)
        try {
            assertTrue(store.contains(handle))
            assertEquals(secret, store.resolve(handle))
            val persisted = context.getSharedPreferences(
                "mistbell-v3-encrypted-provider-credentials",
                Context.MODE_PRIVATE,
            ).getString(handle, null)
            assertTrue("Encrypted credential must be persisted.", !persisted.isNullOrBlank())
            assertFalse("Raw credential must never be persisted.", persisted!!.contains(secret))
        } finally {
            store.delete(handle)
        }
    }

    @Test
    fun missingAndMalformedHandlesDoNotResolve() {
        val store = AndroidKeystoreMemoryCredentialStore(context)
        assertNull(store.resolve("not-a-uuid"))
        assertNull(store.resolve("00000000-0000-0000-0000-000000000000"))
        assertFalse(store.contains("not-a-uuid"))
        assertFalse(store.delete("not-a-uuid"))
    }

    @Test
    fun deletingCredentialMakesHandleUnavailable() {
        val store = AndroidKeystoreMemoryCredentialStore(context)
        val handle = store.put("delete-me-secret")
        assertTrue(store.delete(handle))
        assertFalse(store.contains(handle))
        assertNull(store.resolve(handle))
        assertFalse(store.delete(handle))
    }

    @Test
    fun modifiedCiphertextFailsClosed() {
        val store = AndroidKeystoreMemoryCredentialStore(context)
        val handle = store.put("tamper-test-secret")
        val preferences = context.getSharedPreferences(
            "mistbell-v3-encrypted-provider-credentials",
            Context.MODE_PRIVATE,
        )
        try {
            val encoded = requireNotNull(preferences.getString(handle, null))
            val first = if (encoded.first() == 'A') 'B' else 'A'
            assertTrue(preferences.edit().putString(handle, first + encoded.drop(1)).commit())
            assertNull("Authenticated decryption must reject modified ciphertext.", store.resolve(handle))
        } finally {
            store.delete(handle)
        }
    }
}
