package com.mistbell.tavern.v3.feature.chat

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class SessionArchive(
    val id: String,
    val title: String,
    val messages: List<PersistedChatMessage>,
)

/** Bounded JSON/JSONL import and export for v3 sessions, including common Tavern JSONL fields. */
object SessionArchiveCodec {
    const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
    const val MAX_IMPORT_MESSAGES = 100_000
    const val MAX_IMPORT_LINE_CHARS = 1_000_000

    fun exportJson(archive: SessionArchive): String {
        validate(archive)
        val messages = JSONArray()
        archive.messages.forEach { message ->
            messages.put(JSONObject().put("id", message.id).put("role", message.role)
                .put("content", message.content).put("created_at", message.createdAtEpochMillis)
                .put("delivery_state", message.deliveryState.name))
        }
        return JSONObject().put("format", "mistbell-session").put("version", 1)
            .put("session", JSONObject().put("id", archive.id).put("title", archive.title))
            .put("messages", messages).toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) }
    }

    fun exportJsonl(archive: SessionArchive): String {
        validate(archive)
        val lines = buildString {
            appendLine(JSONObject().put("type", "session")
                .put("session", JSONObject().put("id", archive.id).put("title", archive.title)))
            archive.messages.forEach { message ->
                appendLine(JSONObject().put("id", message.id).put("role", message.role)
                    .put("content", message.content).put("mes", message.content)
                    .put("is_user", message.role == "user").put("is_system", message.role == "system")
                    .put("send_date", message.createdAtEpochMillis.toString())
                    .put("delivery_state", message.deliveryState.name))
            }
        }
        require(lines.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES)
        return lines
    }

    fun importAny(raw: String): SessionArchive? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // JSONL records also begin with '{'. Try each parser independently so a
        // whole-document JSON parse failure does not prevent valid JSONL fallback.
        val parsedJson = runCatching { parseJson(trimmed) }.getOrNull()
        if (parsedJson != null) return parsedJson

        // Do not reinterpret a complete JSON session document with unsupported
        // metadata as a JSONL message. Single-record JSONL remains supported.
        val jsonRoot = runCatching { JSONObject(trimmed) }.getOrNull()
        if (jsonRoot != null && (jsonRoot.has("format") || jsonRoot.has("version") ||
                jsonRoot.has("messages"))) {
            return null
        }
        return runCatching { parseJsonl(trimmed) }.getOrNull()
    }

    private fun parseJson(raw: String): SessionArchive? {
        val root = JSONObject(raw)
        // Explicit Mistbell metadata must be recognized and supported. Missing metadata
        // remains accepted for legacy session JSON files with a top-level messages array.
        if (root.has("format") && root.optString("format") != "mistbell-session") return null
        if (root.has("version") && root.optInt("version", -1) != 1) return null
        val array = root.optJSONArray("messages") ?: return null
        if (array.length() > MAX_IMPORT_MESSAGES) return null
        val session = root.optJSONObject("session") ?: JSONObject()
        val messages = (0 until array.length()).map { index -> parseMessage(array.getJSONObject(index), index) }
        val archive = SessionArchive(
            id = session.optString("id", "").ifBlank { UUID.randomUUID().toString() },
            title = session.optString("title", "Imported session").ifBlank { "Imported session" },
            messages = messages,
        )
        validate(archive)
        return archive
    }

    private fun parseJsonl(raw: String): SessionArchive? {
        if (raw.length > MAX_IMPORT_BYTES) return null
        var id = UUID.randomUUID().toString()
        var title = "Imported session"
        val messages = mutableListOf<PersistedChatMessage>()
        var hasSessionHeader = false
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            require(line.length <= MAX_IMPORT_LINE_CHARS) { "JSONL line exceeds the import limit." }
            val item = JSONObject(line)
            if (item.optString("type") == "session" || item.has("session")) {
                hasSessionHeader = true
                val session = item.optJSONObject("session")
                if (session != null) {
                    id = session.optString("id", id).ifBlank { id }
                    title = session.optString("title", session.optString("name", title)).ifBlank { title }
                }
                return@forEach
            }
            if (messages.size >= MAX_IMPORT_MESSAGES) throw IllegalArgumentException("Too many messages.")
            messages += parseMessage(item, messages.size)
        }
        // An exported header-only archive is a valid empty session. Reject non-empty
        // JSONL that contains neither a session header nor any message records.
        if (messages.isEmpty() && raw.isNotBlank() && !hasSessionHeader) return null
        return SessionArchive(id, title, messages).also(::validate)
    }

    private fun parseMessage(item: JSONObject, index: Int): PersistedChatMessage {
        val role = item.optString("role").takeIf { it in setOf("user", "assistant", "system") }
            ?: when {
                item.optBoolean("is_system") -> "system"
                item.optBoolean("is_user") -> "user"
                else -> "assistant"
            }
        val content = item.optString("content", item.optString("mes", ""))
        require(content.length <= PersistedChatMessage.MAX_MESSAGE_CHARS)
        val timestampRaw = item.opt("created_at")?.toString() ?: item.opt("send_date")?.toString()
        val timestamp = parseTimestamp(timestampRaw)
        val state = runCatching {
            PersistedChatMessage.DeliveryState.valueOf(item.optString("delivery_state", "COMPLETE"))
        }.getOrDefault(PersistedChatMessage.DeliveryState.COMPLETE)
        return PersistedChatMessage(
            id = item.optString("id", "").ifBlank { "imported-" + index + "-" + UUID.randomUUID() },
            role = role, content = content, createdAtEpochMillis = timestamp, deliveryState = state,
        )
    }

    private fun parseTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return runCatching { Instant.parse(raw).toEpochMilli().coerceAtLeast(0L) }.getOrDefault(0L)
    }

    private fun validate(archive: SessionArchive) {
        require(archive.id.isNotBlank())
        require(archive.title.length <= 500)
        require(archive.messages.size <= MAX_IMPORT_MESSAGES)
        require(archive.messages.map(PersistedChatMessage::id).distinct().size == archive.messages.size)
    }
}
