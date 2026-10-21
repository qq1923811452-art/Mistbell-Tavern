package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.PassageEmbeddingRuntime
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicVectorSnapshotStore
import com.mistbell.tavern.v3.core.model.ProviderProfileSet

/**
 * Keeps chat-screen memory scheduling in one cohesive coordinator.
 *
 * This class intentionally delegates persistence, extraction, and index-building work to the
 * existing specialized coordinators; it only wires the shared screen lifecycle dependencies.
 */
internal class ChatMemoryOperationsCoordinator(
    private val context: Context,
    private val memoryCredentialStore: AndroidKeystoreMemoryCredentialStore,
    private val historyStore: SqliteChatHistoryRepository,
    private val historySaveMutex: Mutex,
    private val coroutineScope: CoroutineScope,
    private val diagnosticStore: DiagnosticLogStore,
    private val memoryScopeId: String,
    private val isIndexRebuildInFlight: () -> Boolean,
    private val setIndexRebuildInFlight: (Boolean) -> Unit,
    private val isChatBusy: () -> Boolean,
    private val setStatus: (String) -> Unit,
) {
    fun scheduleIndexRebuild(
        records: List<MemoryTextRecord>,
        runtime: PassageEmbeddingRuntime,
        memoryTextStore: AtomicMemoryTextStore,
        vectorSnapshotStore: AtomicVectorSnapshotStore,
        reason: String,
    ) {
        ChatMemoryIndexRebuildCoordinator(
            coroutineScope = coroutineScope,
            diagnosticStore = diagnosticStore,
            memoryScopeId = memoryScopeId,
            isInFlight = isIndexRebuildInFlight,
            setInFlight = setIndexRebuildInFlight,
            isBusy = isChatBusy,
            setStatus = setStatus,
        ).schedule(records, runtime, memoryTextStore, vectorSnapshotStore, reason)
    }

    fun scheduleExtractionIfDue(providers: ProviderProfileSet, history: List<ChatLine>) {
        ChatMemoryExtractionSchedulingCoordinator(
            context = context,
            memoryCredentialStore = memoryCredentialStore,
            historyStore = historyStore,
            historySaveMutex = historySaveMutex,
            coroutineScope = coroutineScope,
            diagnosticStore = diagnosticStore,
            memoryScopeId = memoryScopeId,
            setStatus = setStatus,
        ).schedule(providers, history)
    }
}
