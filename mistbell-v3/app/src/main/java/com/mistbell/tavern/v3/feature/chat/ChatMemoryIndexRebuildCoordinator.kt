package com.mistbell.tavern.v3.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuilder
import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuildResult
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.PassageEmbeddingRuntime
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicVectorSnapshotStore
import com.mistbell.tavern.v3.core.storage.VectorSnapshotLoadResult

/**
 * Owns the existing background rebuild scheduling and guarded index persistence flow.
 */
internal class ChatMemoryIndexRebuildCoordinator(
    private val coroutineScope: CoroutineScope,
    private val diagnosticStore: DiagnosticLogStore,
    private val memoryScopeId: String,
    private val isInFlight: () -> Boolean,
    private val setInFlight: (Boolean) -> Unit,
    private val isBusy: () -> Boolean,
    private val setStatus: (String) -> Unit,
) {
    fun schedule(
        records: List<MemoryTextRecord>,
        runtime: PassageEmbeddingRuntime,
        memoryTextStore: AtomicMemoryTextStore,
        vectorSnapshotStore: AtomicVectorSnapshotStore,
        reason: String,
    ) {
        if (records.isEmpty()) {
            diagnosticStore.info("memory.index.rebuild_not_needed", "reason=empty_source; scope=${memoryScopeId.take(8)}")
            return
        }
        if (isInFlight()) {
            diagnosticStore.info("memory.index.rebuild_already_running", "scope=${memoryScopeId.take(8)}")
            return
        }
        setInFlight(true)
        val scopePrefix = memoryScopeId.take(8)
        diagnosticStore.info(
            "memory.index.rebuild_scheduled",
            "reason=$reason; records=${records.size}; scope=$scopePrefix",
        )
        coroutineScope.launch(Dispatchers.IO) {
            // Reuse vectors that are already on disk. A missing/corrupt/incompatible snapshot just
            // means a full rebuild, which is the previous behaviour.
            val loaded = vectorSnapshotStore.load(runtime.modelId, runtime.dimensions)
            val previous = (loaded as? VectorSnapshotLoadResult.Loaded)?.snapshot
            val result = MemoryIndexRebuilder(
                runtime = runtime,
                // Source text is already durable; validate it and save vectors under one lock.
                saveSourceTexts = { },
                saveSnapshot = { snapshot ->
                    memoryTextStore.commitIndexIfCurrent(records) { vectorSnapshotStore.save(snapshot) }
                },
                onProgress = { progress ->
                    diagnosticStore.info(
                        "memory.index.rebuild_work",
                        "reused=${progress.reusedRecords}; embedded=${progress.embeddedRecords}; " +
                            "embeddingRequests=${progress.embeddingRequests}; scope=$scopePrefix",
                    )
                },
            ).rebuild(records, previous)
            withContext(Dispatchers.Main) {
                setInFlight(false)
                when (result) {
                    is MemoryIndexRebuildResult.Success -> {
                        diagnosticStore.info(
                            "memory.index.background_rebuild_succeeded",
                            "records=${result.indexedRecords}; dimensions=${result.dimensions}; scope=$scopePrefix",
                        )
                        if (!isBusy()) setStatus("后台记忆索引重建完成：${result.indexedRecords} 条。")
                    }
                    is MemoryIndexRebuildResult.Failed -> {
                        diagnosticStore.warn(
                            "memory.index.background_rebuild_failed",
                            "stage=${result.stage}; reason=${result.reason}; scope=$scopePrefix",
                        )
                        if (!isBusy()) {
                            setStatus(
                                "本地记忆索引重建失败（${result.stage}）；" +
                                    "本轮不会注入记忆，聊天仍可继续。",
                            )
                        }
                    }
                }
            }
        }
    }
}
