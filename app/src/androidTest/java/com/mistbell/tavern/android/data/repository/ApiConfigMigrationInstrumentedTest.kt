package com.mistbell.tavern.android.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for the legacy providers_json migration transaction.
 *
 * These cases protect unreadable encrypted settings from being silently overwritten
 * and verify the already-migrated compatibility path.
 */
@RunWith(AndroidJUnit4::class)
class ApiConfigMigrationInstrumentedTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun unreadableLegacyProvidersJsonAbortsAndPreservesOriginalValue() = runBlocking {
        val unreadableCiphertext = "enc:v1:not-a-valid-envelope"
        db.settingsDao().upsert(SettingsEntity("providers_json", unreadableCiphertext))

        var thrown: IllegalStateException? = null
        try {
            ApiConfigMigration.migrateIfNeeded(db)
        } catch (error: IllegalStateException) {
            thrown = error
        }

        assertNotNull("Migration must fail closed for unreadable legacy ciphertext", thrown)
        assertEquals(
            "The exact legacy value must survive the rolled-back transaction",
            unreadableCiphertext,
            db.settingsDao().getValue("providers_json"),
        )
        assertEquals(null, db.settingsDao().getValue(ApiConfigMigration.MIGRATED_FLAG_KEY))
        assertEquals(0, db.apiConfigDao().getCount())
    }

    @Test
    fun unreadableLegacyProvidersJsonIsPreservedWhenNewConfigsAlreadyExist() = runBlocking {
        val unreadableCiphertext = "enc:v1:not-a-valid-envelope"
        db.settingsDao().upsert(SettingsEntity("providers_json", unreadableCiphertext))
        db.settingsDao().upsert(SettingsEntity(ApiConfigMigration.MIGRATED_FLAG_KEY, "1"))

        ApiConfigMigration.migrateIfNeeded(db)

        assertEquals(unreadableCiphertext, db.settingsDao().getValue("providers_json"))
        assertEquals("1", db.settingsDao().getValue(ApiConfigMigration.MIGRATED_FLAG_KEY))
        assertEquals(0, db.apiConfigDao().getCount())
    }
}
