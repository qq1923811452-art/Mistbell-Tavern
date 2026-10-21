package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobRepository
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionQueueWorker

@Composable
internal fun ChatHistoryLoadLifecycle(
    context: Context,
    historyStore: ChatHistoryStore,
    historyLoadCoordinator: ChatHistoryLoadCoordinator,
    diagnosticStore: DiagnosticLogStore,
    history: MutableList<ChatLine>,
    setHistoryReady: (Boolean) -> Unit,
    setHistoryLoadFailed: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
) {
    LaunchedEffect(historyStore) {
        // A role or conversation change must never leave the previous scope's messages on screen.
        setHistoryReady(false)
        setHistoryLoadFailed(false)
        history.clear()
        when (
            historyLoadCoordinator.load(
                onLoaded = { messages, recoveredInterruptedReply ->
                    history.clear()
                    history.addAll(ChatHistorySnapshotFactory.restore(messages))
                    if (recoveredInterruptedReply) {
                        setStatus("已恢复聊天记录；上次未完成的回复已标记为失败，可继续发送新消息。")
                    }
                },
                onRecoverySaveFailed = {
                    setStatus("已恢复未完成回复，但恢复状态暂未保存；请导出诊断日志并稍后重试。")
                },
            )
        ) {
            ChatHistoryLoadResult.Missing -> Unit
            is ChatHistoryLoadResult.Loaded -> Unit
            ChatHistoryLoadResult.Corrupt -> {
                setHistoryLoadFailed(true)
                diagnosticStore.warn("chat.history.load_failed", "reason=corrupt_or_unsupported_format")
                setStatus("聊天记录文件损坏或版本不兼容；为避免覆盖原文件，未自动清空记录。")
            }
            ChatHistoryLoadResult.Unavailable -> {
                setHistoryLoadFailed(true)
                diagnosticStore.warn("chat.history.load_failed", "reason=temporarily_unavailable")
                setStatus("聊天记录暂时无法读取；原文件未修改，请稍后重试。")
            }
        }
        setHistoryReady(true)
        val pendingQueue = withContext(Dispatchers.IO) {
            runCatching {
                MemoryExtractionJobRepository(
                    MemoryExtractionJobStore(context.applicationContext),
                ).list().any { job ->
                    job.state == MemoryExtractionJob.State.PENDING ||
                        job.state == MemoryExtractionJob.State.RUNNING ||
                        job.state == MemoryExtractionJob.State.RETRY_WAIT
                }
            }.getOrElse {
                diagnosticStore.warn("memory.extraction.queue_load_failed", "reason=queue_unavailable")
                false
            }
        }
        if (pendingQueue) MemoryExtractionQueueWorker.enqueue(context.applicationContext)
    }

}
