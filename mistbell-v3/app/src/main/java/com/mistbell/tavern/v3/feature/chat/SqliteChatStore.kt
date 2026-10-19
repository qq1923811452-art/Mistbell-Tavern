package com.mistbell.tavern.v3.feature.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite-backed incremental chat persistence. This foundation is intentionally not wired into
 * ChatScreen yet; migration and call-site switching belong in a separate integration change.
 */
class SqliteChatStore(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, validateDatabaseName(databaseName), null, DATABASE_VERSION),
    ChatStore {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) createSchema(db)
        // Future schema changes must be additive and versioned here.
    }

    override fun append(scopeId: String, message: PersistedChatMessage) {
        validateScope(scopeId)
        writableDatabase.insertOrThrow(TABLE_MESSAGES, null, values(scopeId, message))
    }

    override fun upsert(scopeId: String, message: PersistedChatMessage) {
        validateScope(scopeId)
        writableDatabase.insertWithOnConflict(
            TABLE_MESSAGES,
            null,
            values(scopeId, message),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun loadRecent(
        scopeId: String,
        limit: Int,
        before: ChatMessageCursor?,
    ): List<PersistedChatMessage> {
        validateScope(scopeId)
        require(limit in 1..MAX_PAGE_SIZE) { "Page size must be between 1 and $MAX_PAGE_SIZE." }
        val selection: String
        val args: Array<String>
        if (before == null) {
            selection = "scope_id = ?"
            args = arrayOf(scopeId)
        } else {
            selection = "scope_id = ? AND (created_at < ? OR (created_at = ? AND id < ?))"
            args = arrayOf(
                scopeId,
                before.createdAtEpochMillis.toString(),
                before.createdAtEpochMillis.toString(),
                before.id,
            )
        }
        return readableDatabase.query(
            TABLE_MESSAGES,
            MESSAGE_COLUMNS,
            selection,
            args,
            null,
            null,
            "created_at DESC, id DESC",
            limit.toString(),
        ).use { cursor ->
            buildList(cursor.count) {
                while (cursor.moveToNext()) add(cursor.toPersistedMessage())
            }
        }
    }

    override fun count(scopeId: String): Long {
        validateScope(scopeId)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE scope_id = ?",
            arrayOf(scopeId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    override fun deleteScope(scopeId: String) {
        validateScope(scopeId)
        writableDatabase.delete(TABLE_MESSAGES, "scope_id = ?", arrayOf(scopeId))
    }

    private fun values(scopeId: String, message: PersistedChatMessage) =
        ContentValues(5).apply {
            put("scope_id", scopeId)
            put("id", message.id)
            put("role", message.role)
            put("content", message.content)
            put("created_at", message.createdAtEpochMillis)
            put("delivery_state", message.deliveryState.name)
        }

    private fun Cursor.toPersistedMessage() = PersistedChatMessage(
        id = getString(getColumnIndexOrThrow("id")),
        role = getString(getColumnIndexOrThrow("role")),
        content = getString(getColumnIndexOrThrow("content")),
        createdAtEpochMillis = getLong(getColumnIndexOrThrow("created_at")),
        deliveryState = PersistedChatMessage.DeliveryState.valueOf(
            getString(getColumnIndexOrThrow("delivery_state")),
        ),
    )

    private fun validateScope(scopeId: String) {
        require(scopeId.isNotBlank() && scopeId.length <= MAX_SCOPE_ID_CHARS) {
            "Conversation scope ID must be non-blank and at most $MAX_SCOPE_ID_CHARS characters."
        }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "mistbell-v3-chat.db"
        const val DATABASE_VERSION = 1
        const val MAX_PAGE_SIZE = 500
        const val MAX_SCOPE_ID_CHARS = 256
        private const val TABLE_MESSAGES = "chat_messages"
        private val MESSAGE_COLUMNS = arrayOf(
            "id",
            "role",
            "content",
            "created_at",
            "delivery_state",
        )

        private fun validateDatabaseName(name: String): String {
            require(name.isNotBlank() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name
            ) { "Database name must be a simple file name." }
            return name
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                    scope_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL CHECK (created_at >= 0),
                    delivery_state TEXT NOT NULL CHECK (
                        delivery_state IN ('COMPLETE', 'PENDING', 'FAILED')
                    ),
                    PRIMARY KEY (scope_id, id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chat_messages_scope_created_id " +
                    "ON $TABLE_MESSAGES(scope_id, created_at DESC, id DESC)",
            )
        }
    }
}
