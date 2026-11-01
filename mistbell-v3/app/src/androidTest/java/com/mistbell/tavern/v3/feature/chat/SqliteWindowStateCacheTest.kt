package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteWindowStateCacheTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var store: SqliteChatStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "window-test-${UUID.randomUUID()}.db"
        store = SqliteChatStore(context, name)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(name)
    }

    @Test
    fun survivesReopenAndRejectsStaleFingerprintAfterEdit() {
        val source = listOf(WindowHistoryMessage("user", PromptRole.USER, "original"))
        val planner = StableConversationWindowPlanner()
        val config = HistoryWindowConfig()
        planner.plan(SCOPE, source, config)
        val state = checkNotNull(planner.exportState())
        SqliteWindowStateCache(store).save(state)
        store.close()
        store = SqliteChatStore(context, name)
        val cached = checkNotNull(SqliteWindowStateCache(store).load(SCOPE))
        assertEquals(state, cached)
        assertTrue(StableConversationWindowPlanner().restore(cached, SCOPE, source, config))
        val edited = listOf(source.first().copy(content = "edited"))
        assertFalse(StableConversationWindowPlanner().restore(cached, SCOPE, edited, config))
    }

    @Test
    fun upgradesSchemaThreeWithoutLosingMessagesAndClearsDeletedScope() {
        store.append(
            SCOPE,
            PersistedChatMessage(
                "kept", "user", "kept content", 1L, PersistedChatMessage.DeliveryState.COMPLETE,
            ),
        )
        store.writableDatabase.execSQL("DROP TABLE window_state")
        store.writableDatabase.version = 3
        store.close()
        store = SqliteChatStore(context, name)
        assertEquals(4, store.writableDatabase.version)
        assertEquals("kept content", store.loadRecent(SCOPE, 1).single().content)
        val planner = StableConversationWindowPlanner()
        planner.plan(SCOPE, emptyList(), HistoryWindowConfig())
        val cache = SqliteWindowStateCache(store)
        cache.save(checkNotNull(planner.exportState()))
        assertTrue(cache.load(SCOPE) != null)
        assertNull(cache.load("other"))
        store.deleteScope(SCOPE)
        assertNull(cache.load(SCOPE))
    }

    @Test
    fun brokenCacheTableDoesNotBlockMessagesAndSummaryIsNeverTruncated() {
        val planner = StableConversationWindowPlanner()
        planner.plan(SCOPE, emptyList(), HistoryWindowConfig(summaryMaxChars = 0))
        val cache = SqliteWindowStateCache(store)
        cache.save(checkNotNull(planner.exportState()).copy(summary = "too long"))
        assertNull(cache.load(SCOPE))
        store.writableDatabase.execSQL("DROP TABLE window_state")
        cache.save(checkNotNull(planner.exportState()))
        assertNull(cache.load(SCOPE))
        assertEquals(0L, store.count(SCOPE))
    }
}

private const val SCOPE = "session"
