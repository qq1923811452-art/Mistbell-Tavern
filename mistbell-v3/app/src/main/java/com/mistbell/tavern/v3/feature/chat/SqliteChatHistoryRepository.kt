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
    /** Older builds stored this conversation under role+conversation; migrate it on first load. */
    private val legacyScopeId: String? = null,
    /** JSON file used by builds that predate conversation-only scopes. */
    private val legacyFileStore: ChatHistoryStore? = null,
) : Closeable {
    private val store = SqliteChatStore(context.applicationContext, databaseName)
    private var cachedById: Map<String, PersistedChatMessage>? = null

    @Synchronized
    fun load(): ChatHistoryLoadResult {
        migrateLegacyScopeIfNeeded()
        val existingCount = runCatching { store.count(scopeId) }
            .getOrElse { return ChatHistoryLoadResult.Unavailable }

        if (existingCount > 0L) {
            val loaded = loadAllFromSqlite()
            if (loaded !is ChatHistoryLoadResult.Loaded) return loaded
            cachedById = loaded.messages.associateBy(PersistedChatMessage::id)
            return loaded
        }

        val fallback = when (val older = legacyFileStore?.load()) {
            null, ChatHistoryLoadResult.Missing -> legacyStore.load()
            else -> older
        }
        return when (val legacy = fallback) {
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

    private fun migrateLegacyScopeIfNeeded() {
        val oldScope = legacyScopeId?.takeIf { it.isNotBlank() && it != scopeId }
        if (oldScope != null) {
            val oldCount = runCatching { store.count(oldScope) }.getOrDefault(0L)
            val targetCount = runCatching { store.count(scopeId) }.getOrDefault(0L)
            if (oldCount > 0L && targetCount == 0L && store.migrateScope(oldScope, scopeId)) {
                cachedById = null
            }
        }
    }

    /**
     * Persist only rows that changed since the last successful load/save. A single transaction
     * ensures a process interruption cannot leave a half-written batch.
     */
    /** Deletes every stored message for this conversation scope. */
    @Synchronized
    fun deleteAll() {
        store.deleteScope(scopeId)
        cachedById = emptyMap()
    }

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
            else -> error("Chat history is unavailable; refusing to save over it.")
        }
        val currentById = messages.associateBy(PersistedChatMessage::id)
        val changed = messages.filter { previous[it.id] != it }
        val removed = previous.keys - currentById.keys
        if (changed.isNotEmpty() || removed.isNotEmpty()) {
            store.syncSnapshot(scopeId, changed, removed)
        }
        cachedById = currentById
    }

    /**
     * Replace the imported session in one SQLite transaction. Update the cache only after commit.
     */
    @Synchronized
    fun replaceAll(messages: List<PersistedChatMessage>) {
        require(messages.size <= MAX_SNAPSHOT_MESSAGES) {
            "Chat history contains too many messages for a single UI snapshot."
        }
        require(messages.map(PersistedChatMessage::id).distinct().size == messages.size) {
            "Chat message IDs must be unique."
        }

        val previous = cachedById ?: when (val loaded = load()) {
            is ChatHistoryLoadResult.Loaded -> loaded.messages.associateBy(PersistedChatMessage::id)
            ChatHistoryLoadResult.Missing -> {
                // Another repository instance may have written rows after load() checked the count.
                when (val current = loadAllFromSqlite()) {
                    is ChatHistoryLoadResult.Loaded -> current.messages.associateBy(PersistedChatMessage::id)
                    else -> throw IllegalStateException("Chat history is unavailable; refusing to replace it.")
                }
            }
            else -> throw IllegalStateException("Chat history is unavailable; refusing to replace it.")
        }
        val currentById = messages.associateBy(PersistedChatMessage::id)
        store.syncSnapshot(scopeId, messages, previous.keys - currentById.keys)
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
