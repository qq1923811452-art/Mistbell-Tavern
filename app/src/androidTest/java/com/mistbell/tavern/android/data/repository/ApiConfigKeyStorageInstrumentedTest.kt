package com.mistbell.tavern.android.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.util.SecureStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiConfigKeyStorageInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun migrationEncryptsPreviouslyPlaintextApiKeys() {
        runBlocking {
            database.apiConfigDao().insert(
                ApiConfigEntity(
                    id = "legacy-config",
                    name = "Legacy",
                    apiUrl = "https://example.invalid/v1",
                    apiKey = "legacy-api-key",
                    model = "test-model",
                ),
            )

            ApiConfigMigration.migrateIfNeeded(database)

            val storedKey = database.apiConfigDao().getById("legacy-config")!!.apiKey
            assertTrue(SecureStore.isEncrypted(storedKey))
            assertEquals("legacy-api-key", SecureStore.unwrap(storedKey))
        }
    }

    @Test
    fun completedMigrationPreservesUnreadableLegacyBlobAndEncryptsActiveSecrets() {
        runBlocking {
            val unreadableLegacyBlob = "enc:v1:not-valid-base64"
            database.settingsDao().upsert(SettingsEntity("providers_json", unreadableLegacyBlob))
            database.settingsDao().upsert(SettingsEntity(ApiConfigMigration.MIGRATED_FLAG_KEY, "1"))
            database.settingsDao().upsert(SettingsEntity("llm_api_key", "legacy-global-key"))
            database.apiConfigDao().insert(
                ApiConfigEntity(
                    id = "active-config",
                    name = "Active",
                    apiUrl = "https://example.invalid/v1",
                    apiKey = "active-api-key",
                    model = "test-model",
                ),
            )

            ApiConfigMigration.migrateIfNeeded(database)

            assertEquals(unreadableLegacyBlob, database.settingsDao().getValue("providers_json"))
            val globalKey = database.settingsDao().getValue("llm_api_key")!!
            assertTrue(SecureStore.isEncrypted(globalKey))
            assertEquals("legacy-global-key", SecureStore.unwrap(globalKey))
            val activeKey = database.apiConfigDao().getById("active-config")!!.apiKey
            assertTrue(SecureStore.isEncrypted(activeKey))
            assertEquals("active-api-key", SecureStore.unwrap(activeKey))
        }
    }
}
