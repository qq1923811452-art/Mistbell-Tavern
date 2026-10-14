package com.mistbell.tavern.android.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.android.data.local.AppDatabase
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StructuredMemoryDaoInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
    fun promptCandidatesExcludeSupersededFactsWithJsonWhitespace() = runBlocking {
        database.structuredMemoryDao().insert(
            memory("old", "{ \"status\" : \"superseded\" }", 10),
        )
        database.structuredMemoryDao().insert(
            memory("current", "{\"status\":\"active\"}", 9),
        )

        val candidates = database.structuredMemoryDao().getPromptCandidates("owner", "character", 1)

        assertEquals(listOf("current"), candidates.map { it.content })
    }

    private fun memory(
        content: String,
        structuredData: String,
        importance: Int,
    ) = StructuredMemoryEntity(
            ownerId = "owner",
            characterId = "character",
            sessionId = "session",
            memoryType = "fact",
            title = content,
            content = content,
            structuredData = structuredData,
            importance = importance,
            tags = null,
            keywords = null,
            createdAt = "2026-10-14T00:00:00Z",
            updatedAt = "2026-10-14T00:00:00Z",
            lastAccessedAt = null,
            relatedMessageIds = null,
        )
}
