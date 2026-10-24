package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionInput
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobRepository
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionQueueWorker
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Owns the existing turn batching and durable enqueue flow for memory extraction.
 */
@Suppress("LongParameterList")
internal class ChatMemoryExtractionSchedulingCoordinator(
    private val context: Context,
    private val memoryCredentialStore: AndroidKeystoreMemoryCredentialStore,
    private val historyStore: SqliteChatHistoryRepository,
    private val historySaveMutex: Mutex,
    private val coroutineScope: CoroutineScope,
    private val diagnosticStore: DiagnosticLogStore,
    private val memoryScopeId: String,
    private val historyScopeId: String,
    private val setStatus: (String) -> Unit,
) {
    fun schedule(providers: ProviderProfileSet, history: List<ChatLine>) {
        val profile = providers.profileFor(ProviderKind.MEMORY_EXTRACTION) ?: return
        val preferences = context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
        val credentialHandle = preferences.getString("memory_api_credential_handle", null)
            ?.takeIf(memoryCredentialStore::contains)
        if (credentialHandle == null) {
            setStatus("记忆整理任务尚未排队：请先安全保存独立记忆 API Key。")
            return
        }

        val pairs = mutableListOf<Pair<ChatLine, ChatLine>>()
        var index = 0
        while (index + 1 < history.size) {
            val user = history[index]
            val assistant = history[index + 1]
            if (user.role == "user" && assistant.role == "assistant" &&
                assistant.deliveryState == PersistedChatMessage.DeliveryState.COMPLETE
            ) pairs += user to assistant
            index += 1
        }
        val lastSuccessfulTurnId = preferences.getString(
            "memory_extraction_last_success_turn_id_$memoryScopeId",
            null,
        )
        val previousIndex = pairs.indexOfLast { it.first.id == lastSuccessfulTurnId }
        val pending = if (previousIndex >= 0) pairs.drop(previousIndex + 1) else pairs
        if (pending.size < 3) return

        val conversationId = memoryScopeId
        val jobs = pending.chunked(MemoryExtractionInput.MAX_TURNS)
            .filter { it.size >= 3 }
            .mapIndexed { batchIndex, batch ->
                val turnIds = batch.map { it.first.id }
                val stableId = UUID.nameUUIDFromBytes(
                    (conversationId + "|" + turnIds.joinToString("|")).toByteArray(Charsets.UTF_8),
                ).toString()
                MemoryExtractionJob(
                    id = stableId,
                    conversationId = conversationId,
                    historyScopeId = historyScopeId,
                    turnIds = turnIds,
                    providerProfileId = profile.id,
                    credentialHandle = credentialHandle,
                    createdAtEpochMillis = (System.currentTimeMillis().coerceAtLeast(0L) + batchIndex).coerceAtMost(Long.MAX_VALUE),
                )
            }
        val historySnapshot = history.map { line ->
            PersistedChatMessage(
                id = line.id,
                role = line.role,
                content = line.content,
                createdAtEpochMillis = line.createdAtEpochMillis,
                deliveryState = line.deliveryState,
            )
        }
        coroutineScope.launch {
            val queuedCount = runCatching {
                withContext(Dispatchers.IO) {
                    // Share the same mutex as history saves: commit the source snapshot before
                    // a worker can observe jobs that reference its turn IDs.
                    historySaveMutex.withLock {
                        historyStore.save(historySnapshot)
                        val repository = MemoryExtractionJobRepository(
                            MemoryExtractionJobStore(context.applicationContext),
                        )
                        var added = 0
                        jobs.forEach { job ->
                            val existing = repository.list().firstOrNull { it.id == job.id }
                            if (existing == null && repository.enqueue(job)) added += 1
                        }
                        added
                    }
                }
            }.getOrElse { error ->
                diagnosticStore.warn(
                    "memory.extraction.queue_enqueue_failed",
                    "reason=${error::class.java.simpleName}",
                )
                setStatus("记忆整理任务未能安全入队；聊天记录仍保留，稍后可重试。")
                return@launch
            }
            MemoryExtractionQueueWorker.enqueue(context.applicationContext)
            diagnosticStore.info("memory.extraction.queue_enqueued", "jobs=$queuedCount")
            setStatus(if (queuedCount > 0) {
                "已将 $queuedCount 批记忆整理任务写入持久化队列；后台任务会独立重试，不影响聊天回复。"
            } else {
                "记忆整理队列已检查；已有任务不会重复入队。"
            })
        }
    }
}
