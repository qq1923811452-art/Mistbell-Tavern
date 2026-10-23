package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.v3.feature.sessions.SessionKind
import com.mistbell.tavern.v3.feature.sessions.SessionParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns serialization and writing of the existing JSON and JSONL session exports.
 */
internal class ChatSessionExportCoordinator(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val setStatus: (String) -> Unit,
) {
    @Suppress("LongParameterList")
    fun export(
        uri: Uri,
        conversationId: String,
        history: List<ChatLine>,
        jsonLines: Boolean,
        title: String = "Mistbell Tavern session",
        kind: SessionKind = SessionKind.SINGLE,
        participants: List<SessionParticipant> = emptyList(),
    ) {
        coroutineScope.launch {
            runCatching {
                val archive = SessionArchive(
                    id = conversationId,
                    title = title,
                    kind = kind,
                    participants = participants,
                    messages = history.map { line ->
                        PersistedChatMessage(
                            id = line.id,
                            role = line.role,
                            content = line.content,
                            createdAtEpochMillis = line.createdAtEpochMillis,
                            deliveryState = line.deliveryState,
                            speakerId = line.speakerId,
                            speakerName = line.speakerName,
                            variants = line.variants,
                            activeVariant = line.activeVariant,
                        )
                    },
                )
                val raw = if (jsonLines) {
                    SessionArchiveCodec.exportJsonl(archive)
                } else {
                    SessionArchiveCodec.exportJson(archive)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法写入导出文件")
                }
            }.onSuccess {
                setStatus(if (jsonLines) "会话 JSONL 导出完成。" else "会话 JSON 导出完成。")
            }.onFailure { error ->
                setStatus("会话导出失败：${error.message ?: error::class.java.simpleName}")
            }
        }
    }
}
