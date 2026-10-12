package com.mistbell.tavern.android.util

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_CLASSIC
import com.mistbell.tavern.android.data.api.model.SessionSummary
import kotlinx.serialization.json.*
import java.io.InputStream

object SessionImporter {
    private val json = Json { ignoreUnknownKeys = true }

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
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader()?.use { it.readText() } ?: return null

            json.decodeFromString<SessionExportData>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importFromJsonl(context: Context, uri: Uri): SessionExportData? {
        return try {
            val text =
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return null
            parseJsonlSessionData(text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 自动识别 JSON 包或 JSONL，方便文件选择器不依赖扩展名。 */
    fun importFromAny(context: Context, uri: Uri): SessionExportData? {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return null
        return try {
            json.decodeFromString<SessionExportData>(text)
        } catch (_: Exception) {
            parseJsonlSessionData(text)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun parseJsonlSessionData(jsonl: String): SessionExportData? {
        val lines = jsonl.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        var session: SessionSummary? = null
        val messages = mutableListOf<Message>()
        lines.forEach { line ->
            val element = json.parseToJsonElement(line).jsonObject
            if (element["type"]?.jsonPrimitive?.contentOrNull == "session") {
                session = element["session"]?.let { json.decodeFromJsonElement<SessionSummary>(it) }
            } else if (element["chat_metadata"] != null || element["type"]?.jsonPrimitive?.contentOrNull == "metadata") {
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
                val content = element["content"]?.jsonPrimitive?.contentOrNull
                    ?: element["mes"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                messages +=
                    Message(
                        id = element["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        role = role,
                        content = content,
                        thinking = element["thinking"]?.jsonPrimitive?.contentOrNull,
                        characterId =
                            element["characterId"]?.jsonPrimitive?.contentOrNull
                                ?: element["name"]?.jsonPrimitive?.contentOrNull.takeIf { role == "assistant" }.orEmpty(),
                        createdAt = element["createdAt"]?.jsonPrimitive?.contentOrNull
                            ?: element["send_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        swipeIndex = element["swipeIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                        swipes = element["swipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                        thinkingSwipes = element["thinkingSwipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                    )
            }
        }
        val resolvedSession = session ?: SessionSummary(
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
    fun parseSessionData(jsonString: String): Pair<SessionSummary, List<Message>>? {
        return try {
            val jsonElement = json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject

            // 解析会话信息
            val sessionObj = jsonObject["session"]?.jsonObject ?: return null
            val session =
                SessionSummary(
                    id = sessionObj["id"]?.jsonPrimitive?.content ?: "",
                    title = sessionObj["title"]?.jsonPrimitive?.content ?: "未命名对话",
                    createdAt = sessionObj["createdAt"]?.jsonPrimitive?.content ?: "",
                    updatedAt = sessionObj["updatedAt"]?.jsonPrimitive?.content ?: "",
                    messageCount = sessionObj["messageCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    characterId = sessionObj["characterId"]?.jsonPrimitive?.content,
                    characterName = sessionObj["characterName"]?.jsonPrimitive?.content,
                    // 契约 6 导入保真：读会话模式，缺失时默认 classic（兼容旧格式导出文件）
                    mode = sessionObj["mode"]?.jsonPrimitive?.content ?: SESSION_MODE_CLASSIC,
                )

            // 解析消息列表
            val messagesArray = jsonObject["messages"]?.jsonArray ?: JsonArray(emptyList())
            val messages =
                messagesArray.mapNotNull { msgElement ->
                    val msgObj = msgElement.jsonObject
                    Message(
                        id = msgObj["id"]?.jsonPrimitive?.content ?: "",
                        role = msgObj["role"]?.jsonPrimitive?.content ?: "user",
                        content = msgObj["content"]?.jsonPrimitive?.content ?: "",
                        thinking = msgObj["thinking"]?.jsonPrimitive?.content,
                        // 契约 6 导入保真：读消息归属（群聊=说话 NPC id），缺失时默认空串
                        // （空串 = 导入侧按会话主角色处理，与 Message 默认值语义一致）
                        characterId = msgObj["characterId"]?.jsonPrimitive?.content ?: "",
                        createdAt = msgObj["createdAt"]?.jsonPrimitive?.content ?: "",
                        memoryIds = msgObj["memoryIds"]?.jsonArray?.map { it.jsonPrimitive.content },
                        swipes = msgObj["swipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                        swipeIndex = msgObj["swipeIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                        thinkingSwipes = msgObj["thinkingSwipes"]?.jsonArray?.map { it.jsonPrimitive.content },
                    )
                }

            Pair(session, messages)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
