package com.mistbell.tavern.v3.core.storage

import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuildResult
import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuilder
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.PassageEmbeddingRuntime

/**
 * Connects the rebuild algorithm to app-private durable stores.
 *
 * This adapter intentionally receives an already-loaded local embedding runtime. It does not
 * download models, silently switch providers, or claim a hardware backend that was not verified.
 */
class PersistedMemoryIndexRebuilder(
    private val textStore: MemoryTextPersistence,
    private val vectorStore: VectorSnapshotSaver,
    runtime: PassageEmbeddingRuntime,
    clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val rebuilder = MemoryIndexRebuilder(
        runtime = runtime,
        saveSourceTexts = textStore::save,
        saveSnapshot = vectorStore::save,
        clockMillis = clockMillis,
    )

    suspend fun rebuildCurrentIndex(): MemoryIndexRebuildResult {
        val currentRecords = when (val loaded = textStore.load()) {
            MemoryTextLoadResult.Missing -> emptyList()
            is MemoryTextLoadResult.Loaded -> loaded.records
            is MemoryTextLoadResult.Corrupt -> {
                return MemoryIndexRebuildResult.Failed(
                    MemoryIndexRebuildResult.Stage.INVALID_INPUT,
                    "Stored source text is corrupt; refusing to overwrite it during rebuild.",
                )
            }
        }
        return rebuilder.rebuild(currentRecords)
    }

    suspend fun rebuild(records: List<MemoryTextRecord>): MemoryIndexRebuildResult =
        rebuilder.rebuild(records)
}
