package com.mistbell.tavern.v3.feature.chat

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Cache failures are isolated from both message transactions and prompt preparation. */
internal class SqliteWindowStateCache(private val store: SqliteChatStore) : WindowStateCache {
    override fun load(scopeId: String): ConversationWindowState? = runCatching {
        store.readableDatabase.query(
            TABLE, null, "scope_id = ?", arrayOf(scopeId), null, null, null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.state() else null }
    }.onFailure { android.util.Log.w("chat.window.cache", "load failed: ${it.javaClass.simpleName}") }.getOrNull()

    override fun save(state: ConversationWindowState) {
        runCatching {
            require(state.summary.length <= state.config.summaryMaxChars)
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                val existing = load(state.scopeId)
                val sameSource = existing?.sourceFingerprint == state.sourceFingerprint &&
                    existing.config == state.config
                val regresses = sameSource && checkNotNull(existing).archivedMessageCount > state.archivedMessageCount
                if (!regresses) {
                    db.insertWithOnConflict(TABLE, null, values(state), SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }.onFailure { android.util.Log.w("chat.window.cache", "save failed: ${it.javaClass.simpleName}") }
    }

    private fun values(state: ConversationWindowState) = ContentValues().apply {
        put("scope_id", state.scopeId)
        put("version", state.version)
        put("recent_turns", state.config.recentTurns)
        put("summary_max_chars", state.config.summaryMaxChars)
        put("max_history_chars", state.config.maxHistoryChars)
        put("block_turns", state.config.blockTurns)
        put("source_count", state.sourceCount)
        put("source_fingerprint", state.sourceFingerprint)
        put("start_id", state.startId)
        put("through_id", state.summaryThroughId)
        put("archived_count", state.archivedMessageCount)
        put("summary", state.summary)
    }

    private fun Cursor.state() = ConversationWindowState(
        version = int("version"),
        scopeId = text("scope_id"),
        config = HistoryWindowConfig(
            int("recent_turns"), int("summary_max_chars"), int("max_history_chars"), int("block_turns"),
        ),
        sourceCount = int("source_count"),
        sourceFingerprint = text("source_fingerprint"),
        startId = getString(getColumnIndexOrThrow("start_id")),
        summaryThroughId = getString(getColumnIndexOrThrow("through_id")),
        archivedMessageCount = int("archived_count"),
        summary = text("summary"),
    )

    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))

    companion object {
        private const val TABLE = "window_state"

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (scope_id TEXT PRIMARY KEY NOT NULL, " +
                    "version INTEGER NOT NULL, recent_turns INTEGER NOT NULL, summary_max_chars INTEGER NOT NULL, " +
                    "max_history_chars INTEGER NOT NULL, block_turns INTEGER NOT NULL, " +
                    "source_count INTEGER NOT NULL, " +
                    "source_fingerprint TEXT NOT NULL, start_id TEXT, through_id TEXT, " +
                    "archived_count INTEGER NOT NULL, summary TEXT NOT NULL)",
            )
        }

        fun clear(db: SQLiteDatabase, scopeId: String) {
            runCatching { db.delete(TABLE, "scope_id = ?", arrayOf(scopeId)) }
        }
    }
}
