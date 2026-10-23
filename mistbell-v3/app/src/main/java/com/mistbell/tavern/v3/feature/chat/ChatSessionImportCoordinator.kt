package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the existing session archive read, validation, and atomic replacement flow.
 */
@Suppress("LongParameterList")
internal class ChatSessionImportCoordinator(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val historyStore: SqliteChatHistoryRepository,
    private val historySaveMutex: Mutex,
    private val historySaveRevision: AtomicLong,
    private val history: MutableList<ChatLine>,
    private val setStatus: (String) -> Unit,
    private val onImported: () -> Unit = {},
) {
    fun import(uri: Uri) {
        coroutineScope.launch {
            runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= SessionArchiveCodec.MAX_IMPORT_BYTES) {
                                "会话文件超过 32 MiB 导入上限。"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("无法读取会话文件")
                }
                SessionArchiveCodec.importAnyDetailed(raw).let { result ->
                    result.archive ?: error(result.error ?: "会话文件无效。")
                }
            }.onSuccess { archive ->
                val importedLines = archive.messages.map { message ->
                    ChatLine(
                        id = message.id,
                        role = message.role,
                        content = message.content,
                        createdAtEpochMillis = message.createdAtEpochMillis,
                        deliveryState = message.deliveryState,
                        speakerId = message.speakerId,
                        speakerName = message.speakerName,
                        variants = message.variants,
                        activeVariant = message.activeVariant,
                    )
                }
                try {
                    historySaveMutex.withLock {
                        // Keep visible history and save revision unchanged until SQLite commits.
                        withContext(Dispatchers.IO) {
                            historyStore.replaceAll(archive.messages)
                        }
                        // Queued snapshots are stale only after the imported database snapshot is durable.
                        historySaveRevision.incrementAndGet()
                        history.clear()
                        history.addAll(importedLines)
                    }
                    onImported()
                    setStatus("已导入会话「${archive.title}」：${archive.messages.size} 条消息（已保存到本地数据库；原文件不会修改）。")
                } catch (error: Exception) {
                    setStatus("会话导入失败，原聊天记录已保留：${error.message ?: error::class.java.simpleName}")
                }
            }.onFailure { error ->
                setStatus("会话导入失败，原聊天记录已保留：${error.message ?: error::class.java.simpleName}")
            }
        }
    }
}
