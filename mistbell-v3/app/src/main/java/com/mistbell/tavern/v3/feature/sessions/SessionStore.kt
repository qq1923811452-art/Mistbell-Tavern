@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.feature.sessions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable session index for the conversation list.
 *
 * Messages remain in the chat database keyed by scope; this store only tracks ordering, titles
 * and summaries, which keeps the list fast even for long histories.
 */
@Suppress("TooManyFunctions")
class SessionStore(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    init {
        openStores.add(this)
    }

    override fun close() {
        openStores.remove(this)
        super.close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_ORDER_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COLUMN_KIND TEXT NOT NULL DEFAULT 'single'")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COLUMN_PARTICIPANTS TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COLUMN_WORLDBOOK_ID TEXT NOT NULL DEFAULT ''")
        }
    }

    fun list(): List<ChatSession> =
        readableDatabase.query(
            TABLE,
            COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED DESC, $COLUMN_UPDATED_AT DESC",
        ).use { cursor ->
            buildList(cursor.count) {
                while (cursor.moveToNext()) add(cursor.toSession())
            }
        }

    @Suppress("LongParameterList")
    fun create(
        roleId: String,
        title: String,
        id: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
        kind: SessionKind = SessionKind.SINGLE,
        participants: List<SessionParticipant> = emptyList(),
        worldbookId: String = "",
    ): ChatSession {
        val session = ChatSession(
            id = id,
            roleId = roleId,
            title = title.ifBlank { DEFAULT_TITLE },
            updatedAt = now,
            kind = kind,
            participants = participants,
            worldbookId = worldbookId,
        )
        writableDatabase.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put(COLUMN_ID, session.id)
                put(COLUMN_ROLE_ID, session.roleId)
                put(COLUMN_TITLE, session.title)
                put(COLUMN_PREVIEW, session.preview)
                put(COLUMN_UPDATED_AT, session.updatedAt)
                put(COLUMN_MESSAGE_COUNT, session.messageCount)
                put(COLUMN_PINNED, if (session.pinned) 1 else 0)
                put(COLUMN_KIND, session.kind.name.lowercase())
                put(COLUMN_PARTICIPANTS, encodeParticipants(session.participants))
                put(COLUMN_WORLDBOOK_ID, session.worldbookId)
            },
        )
        return session
    }

    private fun encodeParticipants(participants: List<SessionParticipant>): String {
        val array = JSONArray()
        participants.forEach { participant ->
            array.put(JSONObject().put("id", participant.id).put("name", participant.name))
        }
        return array.toString()
    }

    private fun decodeParticipants(raw: String?): List<SessionParticipant> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                val id = node.optString("id", "").trim()
                val name = node.optString("name", "").trim()
                if (id.isEmpty() || name.isEmpty()) null else SessionParticipant(id, name)
            }
        }.getOrDefault(emptyList())
    }

    fun rename(id: String, title: String): Boolean {
        val normalized = title.trim().take(MAX_TITLE_CHARS)
        if (normalized.isEmpty()) return false
        return writableDatabase.update(
            TABLE,
            ContentValues().apply { put(COLUMN_TITLE, normalized) },
            "$COLUMN_ID = ?",
            arrayOf(id),
        ) > 0
    }

    fun setRoleId(id: String, roleId: String): Boolean {
        if (id.isBlank() || roleId.isBlank()) return false
        return writableDatabase.update(
            TABLE,
            ContentValues().apply { put(COLUMN_ROLE_ID, roleId) },
            "$COLUMN_ID = ?",
            arrayOf(id),
        ) > 0
    }

    fun updateImportedMetadata(
        id: String,
        title: String,
        kind: SessionKind,
        participants: List<SessionParticipant>,
    ): Boolean {
        val normalized = title.trim().take(MAX_TITLE_CHARS)
        if (id.isBlank() || normalized.isEmpty()) return false
        return writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COLUMN_TITLE, normalized)
                put(COLUMN_KIND, kind.name.lowercase())
                put(COLUMN_PARTICIPANTS, encodeParticipants(participants))
                put(COLUMN_UPDATED_AT, System.currentTimeMillis())
            },
            "$COLUMN_ID = ?",
            arrayOf(id),
        ) > 0
    }

    fun setWorldbookId(id: String, worldbookId: String): Boolean =
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put(COLUMN_WORLDBOOK_ID, worldbookId) },
            "$COLUMN_ID = ?",
            arrayOf(id),
        ) > 0

    fun setPinned(id: String, pinned: Boolean): Boolean =
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put(COLUMN_PINNED, if (pinned) 1 else 0) },
            "$COLUMN_ID = ?",
            arrayOf(id),
        ) > 0

    fun touch(id: String, preview: String, messageCount: Int, now: Long = System.currentTimeMillis()): Boolean =
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COLUMN_PREVIEW, preview.take(MAX_PREVIEW_CHARS))
                put(COLUMN_MESSAGE_COUNT, messageCount.coerceAtLeast(0))
                put(COLUMN_UPDATED_AT, now)
            },
            "$COLUMN_ID = ?",
            arrayOf(id),
        ) > 0

    fun delete(id: String): Boolean =
        writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id)) > 0

    private fun android.database.Cursor.toSession(): ChatSession = ChatSession(
        id = getString(getColumnIndexOrThrow(COLUMN_ID)),
        roleId = getString(getColumnIndexOrThrow(COLUMN_ROLE_ID)),
        title = getString(getColumnIndexOrThrow(COLUMN_TITLE)),
        preview = getString(getColumnIndexOrThrow(COLUMN_PREVIEW)),
        updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
        messageCount = getInt(getColumnIndexOrThrow(COLUMN_MESSAGE_COUNT)),
        pinned = getInt(getColumnIndexOrThrow(COLUMN_PINNED)) != 0,
        kind = when (getString(getColumnIndexOrThrow(COLUMN_KIND)).lowercase()) {
            "group" -> SessionKind.GROUP
            else -> SessionKind.SINGLE
        },
        participants = decodeParticipants(getString(getColumnIndexOrThrow(COLUMN_PARTICIPANTS))),
        worldbookId = getString(getColumnIndexOrThrow(COLUMN_WORLDBOOK_ID)),
    )

    companion object {
        private val openStores: MutableSet<SessionStore> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

        /** Closes every live handle so a restore can safely replace the database file. */
        fun closeAllOpen(): Int {
            val handles = openStores.toList()
            handles.forEach { runCatching { it.close() } }
            return handles.size
        }

        const val DEFAULT_DATABASE_NAME = "mistbell-v3-sessions.db"
        const val DEFAULT_TITLE = "新对话"
        const val MAX_TITLE_CHARS = 120
        const val MAX_PREVIEW_CHARS = 200

        private const val DATABASE_VERSION = 3
        private const val TABLE = "sessions"
        private const val COLUMN_ID = "id"
        private const val COLUMN_ROLE_ID = "role_id"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_PREVIEW = "preview"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private const val COLUMN_MESSAGE_COUNT = "message_count"
        private const val COLUMN_PINNED = "pinned"
        private const val COLUMN_KIND = "kind"
        private const val COLUMN_PARTICIPANTS = "participants"
        private const val COLUMN_WORLDBOOK_ID = "worldbook_id"

        private val COLUMNS = arrayOf(
            COLUMN_ID, COLUMN_ROLE_ID, COLUMN_TITLE, COLUMN_PREVIEW,
            COLUMN_UPDATED_AT, COLUMN_MESSAGE_COUNT, COLUMN_PINNED, COLUMN_KIND, COLUMN_PARTICIPANTS,
            COLUMN_WORLDBOOK_ID,
        )

        private const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_ROLE_ID TEXT NOT NULL,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_PREVIEW TEXT NOT NULL DEFAULT '',
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                $COLUMN_MESSAGE_COUNT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PINNED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_KIND TEXT NOT NULL DEFAULT 'single',
                $COLUMN_PARTICIPANTS TEXT NOT NULL DEFAULT '[]',
                $COLUMN_WORLDBOOK_ID TEXT NOT NULL DEFAULT ''
            )
        """

        private const val CREATE_ORDER_INDEX =
            "CREATE INDEX idx_sessions_order ON $TABLE ($COLUMN_PINNED DESC, $COLUMN_UPDATED_AT DESC)"
    }
}
