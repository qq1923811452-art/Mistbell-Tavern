package com.mistbell.tavern.v3.feature.sessions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreMetadataTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "sessions-test-${UUID.randomUUID()}.db"
        store = SessionStore(context, databaseName)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun touchWithEmptyPreviewUpdatesCountAndTime() {
        val created = store.create("character-1", "新对话")
        val before = store.list().single()
        assertTrue(store.touch(created.id, "", 0, before.updatedAt + 1_000))
        val updated = store.list().single()
        assertEquals("", updated.preview)
        assertEquals(0, updated.messageCount)
        assertTrue(updated.updatedAt > before.updatedAt)
    }

    @Test
    fun importedMetadataReplacesTypeAndParticipants() {
        val created = store.create("character-1", "新对话")
        assertTrue(
            store.updateImportedMetadata(
                id = created.id,
                title = "导入的群聊",
                kind = SessionKind.GROUP,
                participants = listOf(SessionParticipant("a", "甲"), SessionParticipant("b", "乙")),
            ),
        )
        val updated = store.list().single()
        assertEquals("导入的群聊", updated.title)
        assertEquals(SessionKind.GROUP, updated.kind)
        assertEquals(2, updated.participants.size)
    }
}
