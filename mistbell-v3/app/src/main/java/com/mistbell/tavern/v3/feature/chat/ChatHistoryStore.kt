package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A persisted chat message. IDs and timestamps are stable across process restarts. */
data class PersistedChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val deliveryState: DeliveryState,
) {
    enum class DeliveryState { COMPLETE, PENDING, FAILED }

    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARS)
        require(role == "user" || role == "assistant" || role == "system")
        require(content.length <= MAX_MESSAGE_CHARS)
        require(createdAtEpochMillis >= 0L)
    }

    companion object {
        const val MAX_ID_CHARS = 160
        const val MAX_MESSAGE_CHARS = 100_000
    }
}

sealed interface ChatHistoryLoadResult {
    data object Missing : ChatHistoryLoadResult
    data class Loaded(val messages: List<PersistedChatMessage>) : ChatHistoryLoadResult
    data object Corrupt : ChatHistoryLoadResult
}

/**
 * App-private, atomic persistence for the active conversation.
 * Corrupt data is reported explicitly rather than silently overwritten.
 */
class ChatHistoryStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) {
    private val atomicFile: AtomicFile

    init {
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName,
        ) { "Chat history file name must be a simple file name." }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
    }

    @Synchronized
    fun save(messages: List<PersistedChatMessage>) {
        require(messages.size <= MAX_MESSAGES) { "Chat history contains too many messages." }
        require(messages.map(PersistedChatMessage::id).distinct().size == messages.size) {
            "Chat message IDs must be unique."
        }
        val root = JSONObject().put("version", FORMAT_VERSION)
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role)
                    .put("content", message.content)
                    .put("created_at", message.createdAtEpochMillis)
                    .put("delivery_state", message.deliveryState.name),
            )
        }
        root.put("messages", array)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Chat history exceeds the storage limit." }

        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (failure: Throwable) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }

    @Synchronized
    fun load(): ChatHistoryLoadResult {
        val base = atomicFile.baseFile
        val backup = File(base.path + ".bak")
        if (!base.exists() && !backup.exists()) return ChatHistoryLoadResult.Missing
        if (maxOf(base.length(), backup.length()) > MAX_FILE_BYTES) return ChatHistoryLoadResult.Corrupt
        return try {
            val bytes = atomicFile.openRead().use { it.readBytes() }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("version") == FORMAT_VERSION)
            val array = root.getJSONArray("messages")
            require(array.length() <= MAX_MESSAGES)
            val messages = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                PersistedChatMessage(
                    id = item.getString("id"),
                    role = item.getString("role"),
                    content = item.getString("content"),
                    createdAtEpochMillis = item.getLong("created_at"),
                    deliveryState = PersistedChatMessage.DeliveryState.valueOf(
                        item.getString("delivery_state"),
                    ),
                )
            }
            require(messages.map(PersistedChatMessage::id).distinct().size == messages.size)
            ChatHistoryLoadResult.Loaded(messages)
        } catch (_: IOException) {
            ChatHistoryLoadResult.Corrupt
        } catch (_: Exception) {
            ChatHistoryLoadResult.Corrupt
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-chat-history.json"
        const val MAX_MESSAGES = 2_000
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val FORMAT_VERSION = 1
    }
}
