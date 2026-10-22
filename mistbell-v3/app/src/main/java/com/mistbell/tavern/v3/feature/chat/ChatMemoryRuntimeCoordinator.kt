package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextService
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames
import com.mistbell.tavern.v3.core.storage.AtomicVectorSnapshotStore
import com.mistbell.tavern.v3.core.storage.PersistedVectorSnapshotProvider
import com.mistbell.tavern.v3.core.storage.VectorSnapshotLoadResult
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2MemoryRuntime
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2ModelAssetManager
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2RuntimeAdapter
import com.mistbell.tavern.v3.feature.embedding.EmbeddingEngineProcessHolder
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy
import java.io.File
import com.mistbell.tavern.v3.feature.memory.TemporalFactTextSynchronizer
import com.mistbell.tavern.v3.feature.memory.TemporalFactTextSyncResult
import com.mistbell.tavern.v3.feature.memory.TemporalFactExtractionPipeline
import android.os.Handler
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore

/**
 * Owns the existing local embedding runtime initialization, memory-store maintenance,
 * and memory-aware chat stream selection.
 */
internal class ChatMemoryRuntimeCoordinator(
    private val context: Context,
    private val mainHandler: Handler,
    private val diagnosticStore: DiagnosticLogStore,
    private val memoryScopeId: String,
    private val memoryConversationId: String,
    private val indexRebuildCoordinator: ChatMemoryIndexRebuildCoordinator,
    private val setStatus: (String) -> Unit,
    private val setMemoryStatusNotice: (String) -> Unit,
) {
    suspend fun stream(
        client: OpenAiCompatibleChatClient,
        messages: List<PromptMessage>,
        observer: ChatStreamObserver,
        maxTokensText: String,
    ) {
val generationSwitches = context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
val streamEnabled = generationSwitches.getBoolean("chat_stream_enabled", true)
val thinkingEnabled = generationSwitches.getBoolean("chat_thinking_enabled", false)
val embeddingSettings = context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
val modelAssets = EmbeddingGemma2ModelAssetManager(
    modelDirectory = File(context.filesDir, "models"),
    preferQualcommSm8650Model = embeddingSettings.getBoolean(
        "embedding_qualcomm_sm8650_model",
        false,
    ),
)
val installedModelResult = runCatching {
    withContext(Dispatchers.IO) { modelAssets.verifyInstalled() }
}
val installedModel = installedModelResult.getOrNull()
val configuredDimensions = context.getSharedPreferences(
    "mistbell-v3-settings",
    Context.MODE_PRIVATE,
).getInt("embedding_output_dimensions", 768)
if (installedModel == null || configuredDimensions !in CHAT_EMBEDDING_DIMENSION_OPTIONS || configuredDimensions > EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) {
    if (installedModel == null) {
        diagnosticStore.warn(
            "chat.embedding.unavailable",
            if (installedModelResult.isFailure) "reason=model_asset_verification_failed" else "reason=model_not_installed",
        )
    } else {
        diagnosticStore.warn(
            "chat.embedding.unavailable",
            "reason=unsupported_dimension; dimensions=$configuredDimensions; native_max_dimensions=768",
        )
    }
    setStatus(if (installedModel == null) {
        "本地模型未安装或校验失败，继续普通聊天；向量记忆暂不可用。"
    } else {
        "当前 EmbeddingGemma 2 原生最高支持 768 维；所选 ${configuredDimensions} 维暂不可用于本地记忆，继续普通聊天。"
    })
    client.stream(
        messages,
        observer,
        ChatGenerationOptions(
            maxTokens = maxTokensText.toIntOrNull()?.takeIf { it in 1..200_000 } ?: 4096,
            stream = streamEnabled,
            thinkingEnabled = thinkingEnabled,
        ),
    )
} else {
    // The process holder owns native engine lifetime across UI/background transitions.
    val engine = EmbeddingEngineProcessHolder.acquire(
        context = context.applicationContext,
        modelFile = installedModel.file,
        npuModelCompatible = installedModel.isQualcommSm8650Optimized,
        diagnostics = diagnosticStore,
    )
    val backendStatus = withContext(Dispatchers.IO) { engine.currentStatus() }
    diagnosticStore.info(
        "chat.embedding.backend_initialized",
        "phase=${backendStatus.phase}; backend=${backendStatus.backend}; evidence=${backendStatus.backendEvidence}",
    )
    val adapter = EmbeddingGemma2RuntimeAdapter(
        engine = engine,
        outputDimensions = configuredDimensions,
    )
    val memoryRuntime = EmbeddingGemma2MemoryRuntime(adapter)
    val memoryTextStore = AtomicMemoryTextStore(
        context,
        com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames.forScope(
            AtomicMemoryTextStore.DEFAULT_FILE_NAME,
            memoryScopeId,
        ),
    )
    val vectorSnapshotStore = AtomicVectorSnapshotStore(
        context,
        com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames.forScope(
            AtomicVectorSnapshotStore.DEFAULT_FILE_NAME,
            memoryScopeId,
        ),
    )
    val scopedTemporalFactStore = AtomicTemporalFactStore(
        context.applicationContext,
        com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames.forScope(
            AtomicTemporalFactStore.DEFAULT_FILE_NAME,
            memoryScopeId,
        ),
    )
    // Migrate only legacy facts whose recorded conversation ID matches
    // this active session. Never import the old global vector snapshot,
    // because it may contain mixed sessions/roles.
    withContext(Dispatchers.IO) {
        if (scopedTemporalFactStore.load() ==
            com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult.Missing
        ) {
            when (val legacyFacts = AtomicTemporalFactStore(context.applicationContext).load()) {
                is com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult.Loaded -> {
                    val matchingFacts = legacyFacts.facts
                        .filter { it.conversationId == memoryConversationId }
                        .map { it.copy(conversationId = memoryScopeId) }
                    if (matchingFacts.isNotEmpty()) {
                        scopedTemporalFactStore.save(matchingFacts)
                        if (memoryTextStore.load() == MemoryTextLoadResult.Missing) {
                            val currentTexts = matchingFacts
                                .filter { it.isCurrent }
                                .map { fact ->
                                    com.mistbell.tavern.v3.core.memory.MemoryTextRecord(
                                        id = TemporalFactExtractionPipeline.FACT_TEXT_ID_PREFIX + fact.id,
                                        text = "${fact.subject} ${fact.predicate}: ${fact.value}",
                                    )
                                }
                            if (currentTexts.isNotEmpty()) memoryTextStore.save(currentTexts)
                        }
                        diagnosticStore.info(
                            "memory.scope.legacy_migration_succeeded",
                            "facts=${matchingFacts.size}; currentFacts=${matchingFacts.count { it.isCurrent }}; scope=${memoryScopeId.take(8)}",
                        )
                    }
                }
                is com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult.Corrupt -> {
                    diagnosticStore.warn(
                        "memory.scope.legacy_migration_skipped",
                        "reason=legacy_fact_store_corrupt",
                    )
                }
                com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult.Missing -> Unit
            }
        }
    }
    var indexMaintenanceMessage = ""
    when (val syncResult = withContext(Dispatchers.IO) {
        TemporalFactTextSynchronizer(
            factStore = scopedTemporalFactStore,
            memoryTextStore = memoryTextStore,
        ).synchronize()
    }) {
        TemporalFactTextSyncResult.NoFacts -> Unit
        is TemporalFactTextSyncResult.Synchronized -> {
            if (syncResult.changed) {
                diagnosticStore.info(
                    "memory.facts.text_projection_synced",
                    "current_facts=${syncResult.currentFacts}",
                )
            }
        }
        is TemporalFactTextSyncResult.Skipped -> {
            diagnosticStore.warn(
                "memory.facts.text_projection_skipped",
                "reason=safe_sync_failure",
            )
            indexMaintenanceMessage = "事实记忆同步暂不可用；原始事实文件不会被覆盖。"
        }
    }
    when (
        val existingSnapshot = withContext(Dispatchers.IO) {
            vectorSnapshotStore.load(memoryRuntime.modelId, memoryRuntime.dimensions)
        }
    ) {
        is VectorSnapshotLoadResult.Loaded -> {
            when (val sourceTexts = withContext(Dispatchers.IO) { memoryTextStore.load() }) {
                MemoryTextLoadResult.Missing -> Unit
                is MemoryTextLoadResult.Corrupt -> {
                    diagnosticStore.warn(
                        "memory.index.refresh_skipped",
                        "reason=source_text_store_corrupt",
                    )
                    indexMaintenanceMessage = "记忆文本存储校验失败，保留现有向量索引，未自动覆盖。"
                }
                is MemoryTextLoadResult.Loaded -> {
                    val currentHashes = sourceTexts.records.map { it.sourceTextSha256 }.sorted()
                    val indexedHashes = existingSnapshot.snapshot.records.map { it.sourceTextSha256 }.sorted()
                    if (currentHashes != indexedHashes) {
                        indexRebuildCoordinator.schedule(
                            sourceTexts.records,
                            memoryRuntime,
                            memoryTextStore,
                            vectorSnapshotStore,
                            reason = "source_text_changed",
                        )
                        indexMaintenanceMessage = "索引在后台刷新；本轮不会等待重建，变更记忆可能要到后续轮次生效。"
                    }
                }
            }
        }
        is VectorSnapshotLoadResult.Corrupt -> {
            diagnosticStore.warn(
                "memory.index.rebuild_skipped",
                "reason=corrupt_snapshot_preserved",
            )
            indexMaintenanceMessage = "现有向量快照损坏，已保留原文件，未自动覆盖。"
        }
        VectorSnapshotLoadResult.Missing,
        is VectorSnapshotLoadResult.Incompatible -> {
            when (val sourceTexts = withContext(Dispatchers.IO) { memoryTextStore.load() }) {
                MemoryTextLoadResult.Missing -> Unit
                is MemoryTextLoadResult.Corrupt -> {
                    diagnosticStore.warn(
                        "memory.index.rebuild_skipped",
                        "reason=source_text_store_corrupt",
                    )
                    indexMaintenanceMessage = "记忆文本存储校验失败，未覆盖原数据。"
                }
                is MemoryTextLoadResult.Loaded -> {
                    if (sourceTexts.records.isNotEmpty()) {
                        indexRebuildCoordinator.schedule(
                            sourceTexts.records,
                            memoryRuntime,
                            memoryTextStore,
                            vectorSnapshotStore,
                            reason = "snapshot_missing_or_incompatible",
                        )
                        indexMaintenanceMessage = "索引将在后台重建；本轮不会等待重建，记忆注入可能为空。"
                    }
                }
            }
        }
    }
    val memoryContext = VectorMemoryContextService(
        snapshots = PersistedVectorSnapshotProvider(
            store = vectorSnapshotStore,
            expectedModelId = { memoryRuntime.modelId },
            expectedDimensions = { memoryRuntime.dimensions },
        ),
        embeddings = memoryRuntime,
        textProvider = memoryTextStore,
    )
    setStatus("本地记忆检索已接入；后端=${backendStatus.backend}，证据=${backendStatus.backendEvidence}。配置状态不等于芯片级 NPU 实测。" +
        if (indexMaintenanceMessage.isBlank()) "" else " $indexMaintenanceMessage")
    MemoryAwareChatRequest(
        chatClient = client,
        memoryContext = memoryContext,
        onMemoryStatus = { notice ->
            mainHandler.post {
                setMemoryStatusNotice(notice)
                setStatus(notice)
            }
        },
    ).stream(
        messages,
        observer,
        ChatGenerationOptions(
            maxTokens = maxTokensText.toIntOrNull()?.takeIf { it in 1..200_000 } ?: 4096,
            stream = streamEnabled,
            thinkingEnabled = thinkingEnabled,
        ),
    )
        }
    }
}
