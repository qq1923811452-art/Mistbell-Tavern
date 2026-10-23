package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates the existing asynchronous history-save path.
 *
 * Persistence rules remain unchanged: serialize writes, skip superseded snapshots, and report
 * failures through the existing diagnostic store.
 */
internal class ChatHistorySaveCoordinator(
    private val historyStore: SqliteChatHistoryRepository,
    private val coroutineScope: CoroutineScope,
    private val saveMutex: Mutex,
    private val saveRevision: AtomicLong,
    private val diagnosticStore: DiagnosticLogStore,
) {
    fun save(snapshot: List<PersistedChatMessage>) {
        val revision = saveRevision.incrementAndGet()
        coroutineScope.launch {
            saveMutex.withLock {
                // A later UI mutation supersedes an older queued snapshot. Serialize writes and
                // never let a delayed save replace the latest conversation state.
                if (revision != saveRevision.get()) return@withLock
                val startedNanos = System.nanoTime()
                runCatching {
                    withContext(Dispatchers.IO) { historyStore.save(snapshot) }
                }.onSuccess {
                    val elapsed = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
                    diagnosticStore.info(
                        "chat.history.persisted",
                        "messages=${snapshot.size}; elapsedMs=$elapsed",
                    )
                }.onFailure { error ->
                    diagnosticStore.warn(
                        "chat.history.save_failed",
                        "reason=${error::class.java.simpleName}",
                    )
                }
            }
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000
