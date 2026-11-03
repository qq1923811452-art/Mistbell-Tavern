package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobRepository
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionQueueWorker

@Composable
internal fun ChatHistoryLoadLifecycle(
    context: Context,
    historyStore: SqliteChatHistoryRepository,
    historyLoadCoordinator: ChatHistoryLoadCoordinator,
    diagnosticStore: DiagnosticLogStore,
    history: MutableList<ChatLine>,
    setHistoryReady: (Boolean) -> Unit,
    setHistoryLoadFailed: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
) {
    LaunchedEffect(historyStore) {
        val loadStartedNanos = System.nanoTime()
        // A role or conversation change must never leave the previous scope's messages on screen.
        setHistoryReady(false)
        setHistoryLoadFailed(false)
        history.clear()
        var recoveredInterruptedReply = false
        var recoverySaveFailed = false
        val loadOnce: suspend () -> ChatHistoryLoadResult = {
            historyLoadCoordinator.load(
                onLoaded = { messages, recovered ->
                    history.clear()
                    history.addAll(ChatHistorySnapshotFactory.restore(messages))
                    recoveredInterruptedReply = recovered
                },
                onRecoverySaveFailed = { recoverySaveFailed = true },
            )
        }
        var result = loadOnce()
        if (result == ChatHistoryLoadResult.Unavailable) {
            // Startup can race a database replace or an in-flight close; one bounded retry keeps a
            // transient failure from being reported as a permanently empty conversation.
            delay(TRANSIENT_RETRY_DELAY_MILLIS)
            result = loadOnce()
        }
        when (result) {
            ChatHistoryLoadResult.Missing -> Unit
            is ChatHistoryLoadResult.Loaded -> {
                if (result.skippedRows > 0) {
                    setStatus(
                        "已恢复聊天记录；${result.skippedRows} 条无法解析的记录已跳过，" +
                            "其余内容正常显示（原因见诊断日志）。",
                    )
                } else if (recoverySaveFailed) {
                    setStatus("已恢复未完成回复，但恢复状态暂未保存；请导出诊断日志并稍后重试。")
                } else if (recoveredInterruptedReply) {
                    setStatus("已恢复聊天记录；上次未完成的回复已标记为失败，可继续发送新消息。")
                }
            }
            ChatHistoryLoadResult.Corrupt -> {
                setHistoryLoadFailed(true)
                runCatching { diagnosticStore.warn("chat.history.load_failed", "reason=corrupt_or_unsupported_format") }
                setStatus("聊天记录文件损坏或版本不兼容；为避免覆盖原文件，未自动清空记录。")
            }
            ChatHistoryLoadResult.Unavailable -> {
                setHistoryLoadFailed(true)
                runCatching { diagnosticStore.warn("chat.history.load_failed", "reason=temporarily_unavailable; retried=true") }
                setStatus("聊天记录暂时无法读取；原文件未修改，请稍后重试。")
            }
        }
        setHistoryReady(true)
        val loadMillis = (System.nanoTime() - loadStartedNanos) / NANOS_PER_MILLI
        runCatching { diagnosticStore.info("chat.history.loaded", "messages=${history.size}; elapsedMs=$loadMillis") }
        resumePendingExtraction(context, diagnosticStore)
    }
}

/** Restarting the screen must not strand a queue that still has unfinished extraction work. */
private suspend fun resumePendingExtraction(
    context: Context,
    diagnosticStore: DiagnosticLogStore,
) {
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
            runCatching { diagnosticStore.warn("memory.extraction.queue_load_failed", "reason=queue_unavailable") }
            false
        }
    }
    if (pendingQueue) MemoryExtractionQueueWorker.enqueue(context.applicationContext)
}

private const val NANOS_PER_MILLI = 1_000_000
private const val TRANSIENT_RETRY_DELAY_MILLIS = 400L
