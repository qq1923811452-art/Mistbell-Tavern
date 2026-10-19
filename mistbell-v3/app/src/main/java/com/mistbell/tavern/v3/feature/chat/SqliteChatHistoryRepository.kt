package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import java.io.Closeable

/**
 * SQLite-backed chat history with one-time, non-destructive import from the legacy JSON file.
 *
 * The legacy file is intentionally retained after migration as a recovery copy. SQLite becomes
 * the read/write source once it contains messages for this scope.
 */
class SqliteChatHistoryRepository(
    context: Context,
    private val scopeId: String,
    private val legacyStore: ChatHistoryStore,
    databaseName: String = SqliteChatStore.DEFAULT_DATABASE_NAME,
) : Closeable {
    private val store = SqliteChatStore(context.applicationContext, databaseName)
    private var cachedById: Map<String, PersistedChatMessage>? = null

    @Synchronized
    fun load(): ChatHistoryLoadResult {
        val existingCount = runCatching { store.count(scopeId) }
            .getOrElse { return ChatHistoryLoadResult.Unavailable }

        if (existingCount > 0L) {
            val loaded = loadAllFromSqlite()
            if (loaded !is ChatHistoryLoadResult.Loaded) return loaded
            cachedById = loaded.messages.associateBy(PersistedChatMessage::id)
            return loaded
        }

        return when (val legacy = legacyStore.load()) {
            ChatHistoryLoadResult.Missing -> {
                cachedById = emptyMap()
                ChatHistoryLoadResult.Missing
            }
            ChatHistoryLoadResult.Corrupt -> ChatHistoryLoadResult.Corrupt
            ChatHistoryLoadResult.Unavailable -> ChatHistoryLoadResult.Unavailable
            is ChatHistoryLoadResult.Loaded -> {
                try {
                    if (!store.importScopeAtomically(scopeId, legacy.messages)) {
                        cachedById = null
                        return ChatHistoryLoadResult.Unavailable
                    }
                    val migrated = loadAllFromSqlite()
                    if (migrated !is ChatHistoryLoadResult.Loaded) {
                        // The verified transaction has already committed a complete import. Keep
                        // those rows intact; a later load can safely read them without re-importing.
                        cachedById = null
                        return ChatHistoryLoadResult.Unavailable
                    }
                    cachedById = migrated.messages.associateBy(PersistedChatMessage::id)
                    migrated
                } catch (_: Exception) {
                    // Never delete or rewrite the source JSON when migration fails.
                    ChatHistoryLoadResult.Unavailable
                }
            }
        }
    }

    /**
     * Persist only rows that changed since the last successful load/save. A single transaction
     * ensures a process interruption cannot leave a half-written batch.
     */
    @Synchronized
    fun save(messages: List<PersistedChatMessage>) {
        require(messages.size <= MAX_SNAPSHOT_MESSAGES) {
            "Chat history contains too many messages for a single UI snapshot."
        }
        require(messages.map(PersistedChatMessage::id).distinct().size == messages.size) {
            "Chat message IDs must be unique."
        }

        val previous = cachedById ?: when (val loaded = load()) {
            is ChatHistoryLoadResult.Loaded -> loaded.messages.associateBy(PersistedChatMessage::id)
            ChatHistoryLoadResult.Missing -> emptyMap()
            else -> throw IllegalStateException("Chat history is unavailable; refusing to save over it.")
        }
        val currentById = messages.associateBy(PersistedChatMessage::id)
        val changed = messages.filter { previous[it.id] != it }
        val removed = previous.keys - currentById.keys
        if (changed.isNotEmpty() || removed.isNotEmpty()) {
            store.syncSnapshot(scopeId, changed, removed)
        }
        cachedById = currentById
    }

    private fun loadAllFromSqlite(): ChatHistoryLoadResult {
        return try {
            val newestFirst = mutableListOf<PersistedChatMessage>()
            var cursor: ChatMessageCursor? = null
            while (true) {
                val page = store.loadRecent(scopeId, PAGE_SIZE, cursor)
                if (page.isEmpty()) break
                newestFirst += page
                val oldest = page.last()
                cursor = ChatMessageCursor(oldest.createdAtEpochMillis, oldest.id)
                if (page.size < PAGE_SIZE) break
            }
            ChatHistoryLoadResult.Loaded(newestFirst.asReversed())
        } catch (_: Exception) {
            ChatHistoryLoadResult.Unavailable
        }
    }

    /** Wait for any in-flight synchronized load/save before closing the SQLite helper. */
    @Synchronized
    override fun close() {
        store.close()
    }

    companion object {
        private const val PAGE_SIZE = 500
        private const val MAX_SNAPSHOT_MESSAGES = 100_000
    }
}
