package com.mistbell.tavern.v3.feature.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteChatStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var store: SqliteChatStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "chat-test-${UUID.randomUUID()}.db"
        store = SqliteChatStore(context, databaseName)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun storesMessagesIncrementallyAndKeepsConversationScopesIsolated() {
        store.append("role-a/session-1", message("a1", "user", "hello", 10L))
        store.append("role-a/session-1", message("a2", "assistant", "hi", 11L))
        store.append("role-b/session-1", message("b1", "user", "private", 12L))

        assertEquals(2L, store.count("role-a/session-1"))
        assertEquals(1L, store.count("role-b/session-1"))
        assertEquals(listOf("a2", "a1"), store.loadRecent("role-a/session-1", 10).map { it.id })
    }


    @Test
    fun persistsSpeakerAndVariantsAcrossReopen() {
        val scope = "group/session-1"
        val message = PersistedChatMessage(
            id = "m1",
            role = "assistant",
            content = "second variant",
            createdAtEpochMillis = 10L,
            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
            speakerId = "character-2",
            speakerName = "艾莉丝",
            variants = listOf("first variant", "second variant"),
            activeVariant = 1,
        )
        store.upsert(scope, message)
        store.close()
        store = SqliteChatStore(context, databaseName)
        assertEquals(listOf(message), store.loadRecent(scope, 10))
    }

    @Test
    fun pagesOlderMessagesWithoutRepeatingTimestampTies() {
        store.append("session", message("a", "user", "first", 10L))
        store.append("session", message("b", "assistant", "second", 10L))
        store.append("session", message("c", "user", "third", 11L))

        val firstPage = store.loadRecent("session", 2)
        assertEquals(listOf("c", "b"), firstPage.map { it.id })
        val cursor = firstPage.last().let { ChatMessageCursor(it.createdAtEpochMillis, it.id) }
        val nextPage = store.loadRecent("session", 2, cursor)
        assertEquals(listOf("a"), nextPage.map { it.id })
    }

    @Test
    fun upsertIsIdempotentAndUpdatesExistingMessage() {
        store.upsert("session", message("same", "assistant", "pending", 10L,
            PersistedChatMessage.DeliveryState.PENDING))
        store.upsert("session", message("same", "assistant", "complete", 10L,
            PersistedChatMessage.DeliveryState.COMPLETE))

        assertEquals(1L, store.count("session"))
        assertEquals("complete", store.loadRecent("session", 1).single().content)
        assertEquals(PersistedChatMessage.DeliveryState.COMPLETE,
            store.loadRecent("session", 1).single().deliveryState)
    }

    @Test
    fun rejectsInvalidPageSize() {
        assertThrows(IllegalArgumentException::class.java) {
            store.loadRecent("session", 0)
        }
    }

    @Test
    fun deleteScopeDoesNotDeleteOtherConversation() {
        store.append("one", message("1", "user", "one", 1L))
        store.append("two", message("2", "user", "two", 2L))
        store.deleteScope("one")

        assertEquals(0L, store.count("one"))
        assertEquals(1L, store.count("two"))
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
        state: PersistedChatMessage.DeliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
    ) = PersistedChatMessage(id, role, content, createdAt, state)
}
