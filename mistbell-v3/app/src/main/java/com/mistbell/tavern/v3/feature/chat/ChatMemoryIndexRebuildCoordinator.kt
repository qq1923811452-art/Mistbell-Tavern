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
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult

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
            val result = MemoryIndexRebuilder(
                runtime = runtime,
                saveSourceTexts = { rebuiltRecords ->
                    // Do not let a long-running rebuild overwrite facts/manual memories added
                    // after this rebuild took its input snapshot. The vector snapshot is committed
                    // only while the durable source text still matches the records it indexed.
                    when (val latest = memoryTextStore.load()) {
                        MemoryTextLoadResult.Missing -> memoryTextStore.save(rebuiltRecords)
                        is MemoryTextLoadResult.Corrupt -> error(
                            "Source text changed or became unreadable during index rebuild.",
                        )
                        is MemoryTextLoadResult.Loaded -> {
                            val current = latest.records.sortedBy { it.id }
                            val candidate = rebuiltRecords.sortedBy { it.id }
                            check(current == candidate) {
                                "Source text changed during index rebuild."
                            }
                        }
                    }
                },
                saveSnapshot = vectorSnapshotStore::save,
            ).rebuild(records)
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
                        if (!isBusy()) setStatus("本地记忆索引重建失败（${result.stage}）；本轮不会注入记忆，聊天仍可继续。")
                    }
                }
            }
        }
    }
}
