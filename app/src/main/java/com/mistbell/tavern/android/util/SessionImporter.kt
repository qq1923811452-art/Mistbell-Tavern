package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_CLASSIC
import com.mistbell.tavern.android.data.api.model.SessionSummary
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream

object SessionImporter {
    private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
    private const val MAX_IMPORT_MESSAGES = 100_000
    private const val MAX_IMPORT_LINE_CHARS = 1_000_000
    private const val IO_BUFFER_SIZE_BYTES = 8 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    private fun readBoundedText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(IO_BUFFER_SIZE_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_IMPORT_BYTES) { "导入文件超过 32 MiB 限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun validateImportData(data: SessionExportData): SessionExportData {
        require(data.messages.size <= MAX_IMPORT_MESSAGES) { "导入会话的消息数量超出限制" }
        require(data.messages.all { it.content.length <= MAX_IMPORT_LINE_CHARS }) {
            "导入会话中存在超长消息"
        }
        return data
    }

    /**
     * 从 JSON 文件导入会话
     * @param context Android Context
     * @param uri 文件 URI
     * @return SessionExportData 或 null（如果解析失败）
     */
    fun importFromJson(
        context: Context,
        uri: Uri,
    ): SessionExportData? {
        return try {
            val jsonString =
                context.contentResolver.openInputStream(uri)?.use(::readBoundedText) ?: return null
            validateImportData(json.decodeFromString<SessionExportData>(jsonString))
        } catch (_: Exception) {
            null
        }
    }

    fun importFromJsonl(
        context: Context,
        uri: Uri,
    ): SessionExportData? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use(::readBoundedText) ?: return null
            parseJsonlSessionData(text)?.let(::validateImportData)
        } catch (_: Exception) {
            null
        }
    }

    /** 自动识别 JSON 包或 JSONL，方便文件选择器不依赖扩展名。 */
    fun importFromAny(
        context: Context,
        uri: Uri,
    ): SessionExportData? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use(::readBoundedText) ?: return null
            val data =
                try {
                    json.decodeFromString<SessionExportData>(text)
                } catch (_: Exception) {
                    parseJsonlSessionData(text)
                }
            data?.let(::validateImportData)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun parseJsonlSessionData(jsonl: String): SessionExportData? {
        if (jsonl.length > MAX_IMPORT_BYTES) return null
        var session: SessionSummary? = null
        val messages = mutableListOf<Message>()
        jsonl.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            require(line.length <= MAX_IMPORT_LINE_CHARS) { "JSONL 行长度超出限制" }
            val element = json.parseToJsonElement(line).jsonObject
            if (element["type"]?.jsonPrimitive?.contentOrNull == "session") {
                session = element["session"]?.let { json.decodeFromJsonElement<SessionSummary>(it) }
            } else if (
                element["chat_metadata"] != null ||
                element["type"]?.jsonPrimitive?.contentOrNull == "metadata"
            ) {
                // Native Tavern JSONL may contain a metadata line before messages.
                return@forEach
            } else {
                val role =
                    element["role"]?.jsonPrimitive?.contentOrNull
                        ?: when {
                            element["is_system"]?.jsonPrimitive?.booleanOrNull == true -> "system"
                            element["is_user"]?.jsonPrimitive?.booleanOrNull == true -> "user"
                            else -> "assistant"
                        }
                val content =
                    element["content"]?.jsonPrimitive?.contentOrNull
                        ?: element["mes"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                require(messages.size < MAX_IMPORT_MESSAGES) { "导入会话的消息数量超出限制" }
                messages +=
                    Message(
                        id = element["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        role = role,
                        content = content,
                        thinking = element["thinking"]?.jsonPrimitive?.contentOrNull,
                        characterId =
                            element["characterId"]?.jsonPrimitive?.contentOrNull
                                ?: element["name"]?.jsonPrimitive?.contentOrNull
                                    .takeIf { role == "assistant" }.orEmpty(),
                        createdAt =
                            element["createdAt"]?.jsonPrimitive?.contentOrNull
                                ?: element["send_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        swipeIndex = element["swipeIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                        swipes = element["swipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                        thinkingSwipes = element["thinkingSwipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                    )
            }
        }
        val resolvedSession =
            session ?: SessionSummary(
                id = "",
                title = "导入会话",
                messageCount = messages.size,
                mode = SESSION_MODE_CLASSIC,
            )
        return SessionExportData(resolvedSession.copy(messageCount = messages.size), messages)
    }

    /**
     * 解析会话和消息（兼容旧格式）
     */
    fun parseSessionData(jsonString: String): Pair<SessionSummary, List<Message>>? =
        if (jsonString.length > MAX_IMPORT_BYTES) {
            null
        } else {
            try {
                val jsonElement = json.parseToJsonElement(jsonString)
                val jsonObject = jsonElement.jsonObject
                // 解析会话信息；格式不包含 session 时按解析失败处理。
                val sessionObj =
                    jsonObject["session"]?.jsonObject
                        ?: throw IllegalArgumentException("缺少 session 字段")
                val session =
                    SessionSummary(
                        id = sessionObj["id"]?.jsonPrimitive?.content ?: "",
                        title = sessionObj["title"]?.jsonPrimitive?.content ?: "未命名对话",
                        createdAt = sessionObj["createdAt"]?.jsonPrimitive?.content ?: "",
                        updatedAt = sessionObj["updatedAt"]?.jsonPrimitive?.content ?: "",
                        messageCount = sessionObj["messageCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        characterId = sessionObj["characterId"]?.jsonPrimitive?.content,
                        characterName = sessionObj["characterName"]?.jsonPrimitive?.content,
                        mode = sessionObj["mode"]?.jsonPrimitive?.content ?: SESSION_MODE_CLASSIC,
                    )
                val messagesArray = jsonObject["messages"]?.jsonArray ?: JsonArray(emptyList())
                val messages =
                    messagesArray.mapNotNull { msgElement ->
                        val msgObj = msgElement.jsonObject
                        Message(
                            id = msgObj["id"]?.jsonPrimitive?.content ?: "",
                            role = msgObj["role"]?.jsonPrimitive?.content ?: "user",
                            content = msgObj["content"]?.jsonPrimitive?.content ?: "",
                            thinking = msgObj["thinking"]?.jsonPrimitive?.content,
                            characterId = msgObj["characterId"]?.jsonPrimitive?.content ?: "",
                            createdAt = msgObj["createdAt"]?.jsonPrimitive?.content ?: "",
                            memoryIds = msgObj["memoryIds"]?.jsonArray?.map { it.jsonPrimitive.content },
                            swipes = msgObj["swipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                            swipeIndex = msgObj["swipeIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                            thinkingSwipes = msgObj["thinkingSwipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                        )
                    }
                Pair(session, messages)
            } catch (_: Exception) {
                null
            }
        }
}
