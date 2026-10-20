package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteChatHistoryRepositoryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migratesLegacyJsonWithoutDeletingRecoveryCopyAndDoesNotDuplicateOnRestart() {
        val suffix = UUID.randomUUID().toString()
        val databaseName = "history-migration-$suffix.db"
        val legacyName = "history-migration-$suffix.json"
        val legacy = ChatHistoryStore(context, legacyName)
        val original = listOf(
            message("user-1", "user", "hello", 10L),
            message("assistant-1", "assistant", "hi", 11L),
        )
        legacy.save(original)

        val first = SqliteChatHistoryRepository(context, "role/session-$suffix", legacy, databaseName)
        try {
            assertEquals(ChatHistoryLoadResult.Loaded(original), first.load())
        } finally {
            first.close()
        }

        assertEquals("legacy JSON remains available", ChatHistoryLoadResult.Loaded(original), legacy.load())

        val second = SqliteChatHistoryRepository(context, "role/session-$suffix", legacy, databaseName)
        try {
            assertEquals(ChatHistoryLoadResult.Loaded(original), second.load())
            val edited = original.last().copy(content = "updated reply")
            second.save(listOf(original.first(), edited))
            assertEquals(ChatHistoryLoadResult.Loaded(listOf(original.first(), edited)), second.load())
        } finally {
            second.close()
            context.deleteDatabase(databaseName)
            context.deleteFile(legacyName)
            context.deleteFile("$legacyName.bak")
        }
    }

    @Test
    fun saveBeforeFirstLoadImportsLegacyHistoryBeforeWritingNewSnapshot() {
        val suffix = UUID.randomUUID().toString()
        val databaseName = "history-save-first-$suffix.db"
        val legacyName = "history-save-first-$suffix.json"
        val legacy = ChatHistoryStore(context, legacyName)
        val original = listOf(message("legacy-1", "user", "keep this history", 10L))
        legacy.save(original)
        val repository = SqliteChatHistoryRepository(
            context,
            "role/session-$suffix",
            legacy,
            databaseName,
        )
        val appended = message("new-1", "assistant", "new reply", 11L)

        try {
            // Saving can race the initial UI load; it must not make the legacy transcript disappear.
            repository.save(original + appended)
            assertEquals(ChatHistoryLoadResult.Loaded(original + appended), repository.load())
            assertEquals("legacy recovery copy remains intact", ChatHistoryLoadResult.Loaded(original), legacy.load())
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
            context.deleteFile(legacyName)
            context.deleteFile("$legacyName.bak")
        }
    }

    @Test
    fun failedLegacyImportRollsBackEveryInsertedRow() {
        val databaseName = "history-atomic-import-${UUID.randomUUID()}.db"
        val store = SqliteChatStore(context, databaseName)
        try {
            val valid = message("valid", "user", "first row", 1L)
            val invalid = message("invalid", "user", "must fail", 2L)
            store.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_invalid_import
                BEFORE INSERT ON chat_messages
                WHEN NEW.id = 'invalid'
                BEGIN
                    SELECT RAISE(ABORT, 'forced import failure');
                END
                """.trimIndent(),
            )
            var failed = false
            try {
                store.importScopeAtomically("role/session", listOf(valid, invalid))
            } catch (_: android.database.SQLException) {
                failed = true
            }
            assertTrue("invalid row must fail the transaction", failed)
            assertEquals("no partial migration rows may remain", 0L, store.count("role/session"))
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun keepsSqliteScopesIsolatedEvenWhenUsingOneDatabase() {
        val suffix = UUID.randomUUID().toString()
        val databaseName = "history-scopes-$suffix.db"
        val firstLegacyName = "history-first-$suffix.json"
        val secondLegacyName = "history-second-$suffix.json"
        val firstLegacy = ChatHistoryStore(context, firstLegacyName)
        val secondLegacy = ChatHistoryStore(context, secondLegacyName)
        firstLegacy.save(listOf(message("same-id", "user", "first scope", 1L)))
        secondLegacy.save(listOf(message("same-id", "user", "second scope", 1L)))

        val first = SqliteChatHistoryRepository(context, "role-a/session", firstLegacy, databaseName)
        val second = SqliteChatHistoryRepository(context, "role-b/session", secondLegacy, databaseName)
        try {
            assertEquals("first scope", (first.load() as ChatHistoryLoadResult.Loaded).messages.single().content)
            assertEquals("second scope", (second.load() as ChatHistoryLoadResult.Loaded).messages.single().content)
            assertTrue((first.load() as ChatHistoryLoadResult.Loaded).messages.single().id == "same-id")
        } finally {
            first.close()
            second.close()
            context.deleteDatabase(databaseName)
            listOf(firstLegacyName, secondLegacyName).forEach {
                context.deleteFile(it)
                context.deleteFile("$it.bak")
            }
        }
    }

    @Test
    fun replaceAllAtomicallyReplacesSessionAndKeepsCacheAfterFailedImport() {
        val suffix = UUID.randomUUID().toString()
        val databaseName = "history-replace-all-$suffix.db"
        val legacyName = "history-replace-all-$suffix.json"
        val legacy = ChatHistoryStore(context, legacyName)
        val original = listOf(
            message("old-1", "user", "old user", 1L),
            message("old-2", "assistant", "old reply", 2L),
        )
        legacy.save(original)
        val repository = SqliteChatHistoryRepository(
            context,
            "role/session-$suffix",
            legacy,
            databaseName,
        )
        try {
            assertEquals(ChatHistoryLoadResult.Loaded(original), repository.load())

            val imported = listOf(message("new-1", "user", "imported session", 3L))
            repository.replaceAll(imported)
            assertEquals(ChatHistoryLoadResult.Loaded(imported), repository.load())

            val store = SqliteChatStore(context, databaseName)
            try {
                store.writableDatabase.execSQL(
                    """
                    CREATE TRIGGER fail_invalid_replace
                    BEFORE INSERT ON chat_messages
                    WHEN NEW.id = 'invalid'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced replace failure');
                    END
                    """.trimIndent(),
                )
            } finally {
                store.close()
            }

            var failed = false
            try {
                repository.replaceAll(listOf(message("invalid", "assistant", "must roll back", 4L)))
            } catch (_: android.database.SQLException) {
                failed = true
            }
            assertTrue("failed imported snapshot must throw", failed)
            assertEquals("failed replace must keep database rows intact", ChatHistoryLoadResult.Loaded(imported), repository.load())

            val appended = imported + message("new-2", "assistant", "follow-up", 5L)
            repository.save(appended)
            assertEquals("cache must remain aligned after failed replace", ChatHistoryLoadResult.Loaded(appended), repository.load())
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
            context.deleteFile(legacyName)
            context.deleteFile("$legacyName.bak")
        }
    }

    @Test
    fun savingShorterSnapshotDeletesRemovedMessagesFromSqlite() {
        val suffix = UUID.randomUUID().toString()
        val databaseName = "history-delete-sync-$suffix.db"
        val legacyName = "history-delete-sync-$suffix.json"
        val legacy = ChatHistoryStore(context, legacyName)
        val original = listOf(
            message("one", "user", "first", 1L),
            message("two", "assistant", "second", 2L),
            message("three", "user", "third", 3L),
        )
        legacy.save(original)
        val repository = SqliteChatHistoryRepository(
            context,
            "role/session-$suffix",
            legacy,
            databaseName,
        )
        try {
            assertEquals(ChatHistoryLoadResult.Loaded(original), repository.load())
            val retained = listOf(original[0], original[2])
            repository.save(retained)
            assertEquals(
                "removed message must not reappear after a fresh repository reload",
                ChatHistoryLoadResult.Loaded(retained),
                repository.load(),
            )
            assertEquals(
                "legacy recovery copy remains unchanged",
                ChatHistoryLoadResult.Loaded(original),
                legacy.load(),
            )
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
            context.deleteFile(legacyName)
            context.deleteFile("$legacyName.bak")
        }
    }

    @Test
    fun snapshotUpsertsAndDeletionsRollbackTogetherOnFailure() {
        val databaseName = "history-atomic-snapshot-${UUID.randomUUID()}.db"
        val store = SqliteChatStore(context, databaseName)
        val existing = message("keep", "user", "must survive", 1L)
        try {
            store.upsertAll("role/session", listOf(existing))
            val invalid = message("invalid", "user", "must fail", 2L)
            store.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_invalid_snapshot
                BEFORE INSERT ON chat_messages
                WHEN NEW.id = 'invalid'
                BEGIN
                    SELECT RAISE(ABORT, 'forced snapshot failure');
                END
                """.trimIndent(),
            )
            var failed = false
            try {
                store.syncSnapshot("role/session", listOf(invalid), setOf("keep"))
            } catch (_: android.database.SQLException) {
                failed = true
            }
            assertTrue("invalid upsert must fail the snapshot transaction", failed)
            assertEquals(listOf(existing), store.loadRecent("role/session", 10, null).reversed())
            assertEquals(1L, store.count("role/session"))
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
    ) = PersistedChatMessage(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = createdAt,
        deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
    )
}
