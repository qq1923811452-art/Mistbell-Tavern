package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.sessions.SessionKind
import com.mistbell.tavern.v3.feature.sessions.SessionParticipant
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class SessionArchive(
    val id: String,
    val title: String,
    val messages: List<PersistedChatMessage>,
    val kind: SessionKind = SessionKind.SINGLE,
    val participants: List<SessionParticipant> = emptyList(),
)

data class SessionArchiveImportResult(
    val archive: SessionArchive?,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = archive != null
}

/** Bounded JSON/JSONL import and export for v3 sessions, including common Tavern JSONL fields. */
@Suppress("TooManyFunctions")
object SessionArchiveCodec {
    const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
    const val MAX_IMPORT_MESSAGES = 100_000
    const val MAX_IMPORT_LINE_CHARS = 1_000_000

    fun exportJson(archive: SessionArchive): String {
        validate(archive)
        val messages = JSONArray()
        archive.messages.forEach { message ->
            messages.put(messageJson(message))
        }
        return JSONObject().put("format", "mistbell-session").put("version", 1)
            .put("session", sessionJson(archive))
            .put("messages", messages).toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) }
    }

    fun exportJsonl(archive: SessionArchive): String {
        validate(archive)
        val lines = buildString {
            appendLine(JSONObject().put("type", "session").put("session", sessionJson(archive)))
            archive.messages.forEach { message ->
                appendLine(messageJson(message).put("mes", message.content)
                    .put("is_user", message.role == "user").put("is_system", message.role == "system")
                    .put("send_date", message.createdAtEpochMillis.toString()))
            }
        }
        require(lines.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES)
        return lines
    }

    fun importAny(raw: String): SessionArchive? = importAnyDetailed(raw).archive

    /** Returns a user-displayable reason instead of silently collapsing every failure to null. */
    fun importAnyDetailed(raw: String): SessionArchiveImportResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            return SessionArchiveImportResult(null, "文件超过 32 MiB 导入上限。")
        }
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return SessionArchiveImportResult(null, "文件为空。")

        // Enforce the per-line bound before constructing a full JSON tree. This also
        // bounds unusually large lines in JSON documents, not only JSONL records.
        trimmed.lineSequence().forEachIndexed { lineIndex, line ->
            if (line.length > MAX_IMPORT_LINE_CHARS) {
                return SessionArchiveImportResult(
                    null,
                    "第 ${lineIndex + 1} 行超过导入长度上限。",
                )
            }
        }

        // JSONL records also begin with '{'. Try a complete JSON document first.
        val jsonRoot = runCatching { JSONObject(trimmed) }.getOrNull()
        if (jsonRoot != null && (jsonRoot.has("format") || jsonRoot.has("version") ||
                jsonRoot.has("messages"))) {
            val format = jsonRoot.optString("format", "mistbell-session")
            if (format != "mistbell-session") {
                return SessionArchiveImportResult(null, "不支持的会话文件格式：$format。")
            }
            if (jsonRoot.has("version") && jsonRoot.optInt("version", -1) != 1) {
                return SessionArchiveImportResult(null, "不支持的会话文件版本。")
            }
            return runCatching { parseJson(trimmed) }
                .fold(
                    onSuccess = { archive ->
                        if (archive == null) SessionArchiveImportResult(null, "JSON 文件缺少有效的 messages 数组。")
                        else SessionArchiveImportResult(archive)
                    },
                    onFailure = { error ->
                        SessionArchiveImportResult(null, error.message ?: "JSON 会话文件内容无效。")
                    },
                )
        }

        return runCatching { parseJsonl(trimmed) }.fold(
            onSuccess = { archive ->
                if (archive == null) SessionArchiveImportResult(null, "JSONL 文件中没有有效的会话头或消息记录。")
                else SessionArchiveImportResult(archive)
            },
            onFailure = { error ->
                SessionArchiveImportResult(null, error.message ?: "JSONL 会话文件内容无效。")
            },
        )
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
        val messages = (0 until array.length()).map { index ->
            val item = array.opt(index)
            require(item is JSONObject) { "第 ${index + 1} 条消息必须是 JSON 对象。" }
            parseMessage(item, index)
        }
        val archive = SessionArchive(
            id = session.optString("id", "").ifBlank { UUID.randomUUID().toString() },
            title = session.optString("title", "Imported session").ifBlank { "Imported session" },
            messages = messages,
            kind = parseKind(session.optString("kind", "single")),
            participants = parseParticipants(session.optJSONArray("participants")),
        )
        validate(archive)
        return archive
    }

    private fun parseJsonl(raw: String): SessionArchive? {
        if (raw.length > MAX_IMPORT_BYTES) return null
        var id = UUID.randomUUID().toString()
        var title = "Imported session"
        var headerKind = SessionKind.SINGLE
        var headerParticipants: List<SessionParticipant> = emptyList()
        val messages = mutableListOf<PersistedChatMessage>()
        var hasSessionHeader = false
        raw.lineSequence().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            require(line.length <= MAX_IMPORT_LINE_CHARS) { "JSONL line exceeds the import limit." }
            val item = runCatching { JSONObject(line) }.getOrElse {
                throw IllegalArgumentException("第 ${lineIndex + 1} 行不是有效 JSON。")
            }
            if (item.optString("type") == "session" || item.has("session")) {
                hasSessionHeader = true
                val session = item.optJSONObject("session")
                if (session != null) {
                    id = session.optString("id", id).ifBlank { id }
                    title = session.optString("title", session.optString("name", title)).ifBlank { title }
                    headerKind = parseKind(session.optString("kind", "single"))
                    headerParticipants = parseParticipants(session.optJSONArray("participants"))
                }
                return@forEachIndexed
            }
            if (messages.size >= MAX_IMPORT_MESSAGES) throw IllegalArgumentException("Too many messages.")
            messages += parseMessage(item, messages.size)
        }
        // An exported header-only archive is a valid empty session. Reject non-empty
        // JSONL that contains neither a session header nor any message records.
        if (messages.isEmpty() && raw.isNotBlank() && !hasSessionHeader) return null
        return SessionArchive(
            id = id,
            title = title,
            messages = messages,
            kind = headerKind,
            participants = headerParticipants,
        ).also(::validate)
    }

    private fun sessionJson(archive: SessionArchive): JSONObject = JSONObject()
        .put("id", archive.id)
        .put("title", archive.title)
        .put("kind", archive.kind.name.lowercase())
        .put("participants", JSONArray().apply {
            archive.participants.forEach { participant ->
                put(JSONObject().put("id", participant.id).put("name", participant.name))
            }
        })

    private fun messageJson(message: PersistedChatMessage): JSONObject = JSONObject()
        .put("id", message.id)
        .put("role", message.role)
        .put("content", message.content)
        .put("created_at", message.createdAtEpochMillis)
        .put("delivery_state", message.deliveryState.name)
        .also { node ->
            message.speakerId?.let { node.put("speaker_id", it) }
            message.speakerName?.let { node.put("speaker_name", it) }
            if (message.variants.isNotEmpty()) {
                node.put("variants", JSONArray(message.variants))
                node.put("active_variant", message.activeVariant)
            }
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
            speakerId = item.optString("speaker_id", "").takeIf(String::isNotBlank),
            speakerName = item.optString("speaker_name", "").takeIf(String::isNotBlank),
            variants = item.optJSONArray("variants")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    array.optString(i, "").takeIf(String::isNotEmpty)
                }
            }.orEmpty(),
            activeVariant = item.optInt("active_variant", 0),
        )
    }

    private fun parseKind(raw: String): SessionKind =
        if (raw.equals("group", ignoreCase = true)) SessionKind.GROUP else SessionKind.SINGLE

    private fun parseParticipants(array: JSONArray?): List<SessionParticipant> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val participantId = item.optString("id", "").trim()
            val name = item.optString("name", "").trim()
            if (participantId.isEmpty() || name.isEmpty()) null else SessionParticipant(participantId, name)
        }
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
