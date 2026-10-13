package com.mistbell.tavern.android.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
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
}
