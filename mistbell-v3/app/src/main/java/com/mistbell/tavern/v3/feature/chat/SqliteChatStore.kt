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
        upsertAll(scopeId, listOf(message))
    }

    /** Commits a batch atomically; callers should pass only changed/new rows. */
    fun upsertAll(scopeId: String, messages: List<PersistedChatMessage>) {
        syncSnapshot(scopeId, messages, emptySet())
    }

    /**
     * Applies changed/new rows and removals as one transaction, so a crash cannot leave the
     * database with only half of a UI snapshot applied.
     */
    fun syncSnapshot(
        scopeId: String,
        upserts: List<PersistedChatMessage>,
        removedMessageIds: Set<String>,
    ) {
        validateScope(scopeId)
        require(upserts.map(PersistedChatMessage::id).distinct().size == upserts.size) {
            "Chat message IDs must be unique within a batch."
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            upserts.forEach { message ->
                db.insertWithOnConflict(
                    TABLE_MESSAGES,
                    null,
                    values(scopeId, message),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            removedMessageIds.forEach { id ->
                db.delete(TABLE_MESSAGES, "scope_id = ? AND id = ?", arrayOf(scopeId, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Imports a legacy snapshot only when this scope is empty, verifying every row before
     * committing. A mismatch or exception rolls the whole import back, so a later load can retry
     * from the untouched JSON recovery source.
     */
    fun importScopeAtomically(scopeId: String, messages: List<PersistedChatMessage>): Boolean {
        validateScope(scopeId)
        require(messages.map(PersistedChatMessage::id).distinct().size == messages.size) {
            "Chat message IDs must be unique within a batch."
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingCount = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE scope_id = ?",
                arrayOf(scopeId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            if (existingCount != 0L) return false

            messages.forEach { message ->
                db.insertOrThrow(TABLE_MESSAGES, null, values(scopeId, message))
            }
            val imported = db.query(
                TABLE_MESSAGES,
                MESSAGE_COLUMNS,
                "scope_id = ?",
                arrayOf(scopeId),
                null,
                null,
                "created_at ASC, id ASC",
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) add(cursor.toPersistedMessage())
                }
            }
            if (imported.size != messages.size ||
                imported.associateBy(PersistedChatMessage::id) != messages.associateBy(PersistedChatMessage::id)
            ) return false

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
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

    /** Deletes only the specified message IDs within one conversation scope. */
    fun deleteMessages(scopeId: String, messageIds: Set<String>) {
        validateScope(scopeId)
        if (messageIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            messageIds.forEach { id ->
                db.delete(TABLE_MESSAGES, "scope_id = ? AND id = ?", arrayOf(scopeId, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
