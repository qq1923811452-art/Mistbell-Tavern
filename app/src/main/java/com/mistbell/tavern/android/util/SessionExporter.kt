package com.mistbell.tavern.android.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.api.model.SessionSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class SessionExportData(
    val session: SessionSummary,
    val messages: List<Message>,
)

enum class SessionExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    JSON("JSON", "json", "application/json"),
    JSONL("酒馆 JSONL", "jsonl", "application/x-ndjson"),
}

data class SessionExportResult(
    val uri: Uri,
    val fileName: String,
    val location: String,
    val mimeType: String,
)

object SessionExporter {
    private const val EXPORT_FOLDER = "LongMemoryAIChat"

    fun buildFileName(
        title: String,
        sessionId: String,
        extension: String,
    ): String {
        val safeTitle =
            title
                .ifBlank { "session" }
                .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
                .trim()
                .take(28)
                .ifBlank { "session" }
        val shortId = sessionId.take(8).ifBlank { System.currentTimeMillis().toString() }
        return "${safeTitle}_${shortId}_${System.currentTimeMillis()}.$extension"
    }

    fun displayLocation(fileName: String): String {
        return "下载/$EXPORT_FOLDER/$fileName"
    }

    /**
     * 导出会话到 JSON 文件
     * @param context Android Context
     * @param session 会话信息
     * @param messages 会话中的所有消息
     * @return 导出的文件 URI，用于分享
     */
    fun exportToJson(
        context: Context,
        session: SessionSummary,
        messages: List<Message>,
        fileName: String = buildFileName(session.title, session.id, SessionExportFormat.JSON.extension),
    ): SessionExportResult? {
        return try {
            val exportData =
                SessionExportData(
                    session = session,
                    messages = messages,
                )

            val json =
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                }
            val jsonString = json.encodeToString(exportData)

            saveBytes(
                context = context,
                fileName = fileName,
                mimeType = SessionExportFormat.JSON.mimeType,
                bytes = jsonString.toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 酒馆兼容 JSONL：首行保存会话元数据，后续每行一个消息对象。 */
    fun exportToJsonl(
        context: Context,
        session: SessionSummary,
        messages: List<Message>,
        fileName: String = buildFileName(session.title, session.id, SessionExportFormat.JSONL.extension),
    ): SessionExportResult? {
        return try {
            val lines = buildJsonlLines(session, messages)
            saveBytes(
                context = context,
                fileName = fileName,
                mimeType = SessionExportFormat.JSONL.mimeType,
                bytes = lines.toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun buildJsonlLines(
        session: SessionSummary,
        messages: List<Message>,
    ): String {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        return buildString {
            appendLine(
                buildJsonObject {
                    put("type", "session")
                    put("session", json.encodeToJsonElement(SessionSummary.serializer(), session))
                }.toString(),
            )
            messages.forEach { message ->
                appendLine(
                    buildJsonObject {
                        put("id", message.id)
                        put("name", message.characterId)
                        put("is_user", message.role == "user")
                        put("is_system", message.role == "system")
                        put("mes", message.content)
                        put("send_date", message.createdAt)
                        put("role", message.role)
                        put("content", message.content)
                        message.thinking?.let { put("thinking", it) }
                        message.characterId.takeIf { it.isNotBlank() }?.let { put("characterId", it) }
                        message.swipeIndex.let { put("swipeIndex", it) }
                        message.swipes?.let {
                            put(
                                "swipes",
                                json.encodeToJsonElement(
                                    kotlinx.serialization.builtins.ListSerializer(
                                        kotlinx.serialization.serializer<String>(),
                                    ),
                                    it,
                                ),
                            )
                        }
                        message.thinkingSwipes?.let {
                            put(
                                "thinkingSwipes",
                                json.encodeToJsonElement(
                                    kotlinx.serialization.builtins.ListSerializer(
                                        kotlinx.serialization.serializer<String>(),
                                    ),
                                    it,
                                ),
                            )
                        }
                    }.toString(),
                )
            }
        }
    }

    private fun saveBytes(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): SessionExportResult? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER",
                    )
                }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null

            SessionExportResult(
                uri = uri,
                fileName = fileName,
                location = displayLocation(fileName),
                mimeType = mimeType,
            )
        } else {
            val dir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    EXPORT_FOLDER,
                )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeBytes(bytes)

            SessionExportResult(
                uri = Uri.fromFile(file),
                fileName = fileName,
                location = displayLocation(fileName),
                mimeType = mimeType,
            )
        }
    }

    /**
     * 创建分享 Intent
     */
    fun createShareIntent(
        context: Context,
        uri: Uri,
    ): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
