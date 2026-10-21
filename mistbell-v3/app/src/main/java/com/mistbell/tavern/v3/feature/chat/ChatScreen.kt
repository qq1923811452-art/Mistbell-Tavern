package com.mistbell.tavern.v3.feature.chat

import android.os.Handler
import android.util.AtomicFile
import android.os.Looper
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuilder
import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuildResult
import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextService
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryScopeId
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
import java.util.UUID
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.feature.memory.MemoryCredentialResolver
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionClient
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobRepository
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionQueueWorker
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionInput
import com.mistbell.tavern.v3.feature.memory.formatMemoryExtractionTurn
import com.mistbell.tavern.v3.feature.memory.TemporalFactTextSynchronizer
import com.mistbell.tavern.v3.feature.memory.TemporalFactTextSyncResult
import com.mistbell.tavern.v3.feature.memory.TemporalFactExtractionPipeline
import com.mistbell.tavern.v3.feature.memory.TemporalFactPipelineOutcome
import com.mistbell.tavern.v3.feature.character.CharacterCard
import com.mistbell.tavern.v3.feature.character.CharacterCardLibraryStore
import com.mistbell.tavern.v3.feature.worldbook.LocalWorldBookService
import com.mistbell.tavern.v3.feature.worldbook.WorldBookActivationState
import com.mistbell.tavern.v3.feature.worldbook.WorldBookParser

private fun formatCharacterCardForPrompt(card: CharacterCard): String = card.stableDefinition()

internal val CHAT_EMBEDDING_DIMENSION_OPTIONS = listOf(128, 256, 512, 768, 1024, 1536, 2048)

internal data class ChatLine(
    val id: String,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val deliveryState: PersistedChatMessage.DeliveryState,
)

/**
 * Minimal end-to-end chat screen for 3.0 development.
 * Provider settings and the API key remain in screen memory only; they are not persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel()
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val settings = remember(context) {
        context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
    }
    var baseUrl by remember(settings) {
        mutableStateOf(settings.getString("chat_api_base_url", "https://api.odys.work/v1") ?: "https://api.odys.work/v1")
    }
    var modelId by remember(settings) {
        mutableStateOf(settings.getString("chat_model_id", "") ?: "")
    }
    var apiKey by remember { mutableStateOf("") }
    var memoryBaseUrl by remember(settings) {
        mutableStateOf(settings.getString("memory_api_base_url", "https://api.odys.work/v1") ?: "https://api.odys.work/v1")
    }
    var memoryModelId by remember(settings) {
        mutableStateOf(settings.getString("memory_model_id", "") ?: "")
    }
    var memoryApiKey by remember { mutableStateOf("") }
    var hasSavedMemoryCredential by remember(settings) {
        mutableStateOf(
            settings.getString("memory_api_credential_handle", null)?.let { handle ->
                runCatching { AndroidKeystoreMemoryCredentialStore(context.applicationContext).contains(handle) }
                    .getOrDefault(false)
            } ?: false,
        )
    }
    var busy by remember { mutableStateOf(false) }
    var historyReady by remember { mutableStateOf(false) }
    var historyLoadFailed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("填写模型 ID 和 API 配置后即可测试聊天。") }
    var showImportWarning by remember { mutableStateOf(false) }
    var memoryConversationId by remember(settings) {
        mutableStateOf(
            settings.getString("memory_extraction_conversation_id", null)
                ?.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString().also { generated ->
                    settings.edit().putString("memory_extraction_conversation_id", generated).apply()
                },
        )
    }
    var selectedCharacterId by remember(settings) {
        mutableStateOf(settings.getString("active_character_id", "") ?: "")
    }
    val activeMemoryRoleId = selectedCharacterId.takeIf(String::isNotBlank) ?: "default-character"
    val memoryScopeId = remember(activeMemoryRoleId, memoryConversationId) {
        MemoryScopeId.forRoleAndConversation(activeMemoryRoleId, memoryConversationId)
    }
    var memoryIndexRebuildInFlight by remember { mutableStateOf(false) }
    var memoryStatusNotice by remember { mutableStateOf("") }
    var maxTokensText by remember(settings) {
        mutableStateOf(settings.getInt("chat_max_tokens", 4096).coerceIn(1, 200_000).toString())
    }
    var recentTurnsText by remember(settings) {
        mutableStateOf(
            settings.getInt("chat_history_recent_turns", ConversationContextWindowBuilder.DEFAULT_RECENT_TURNS)
                .coerceIn(1, 100).toString(),
        )
    }
    var summaryCharsText by remember(settings) {
        mutableStateOf(
            settings.getInt("chat_history_summary_max_chars", ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS)
                .coerceIn(0, 10_000).toString(),
        )
    }
    var historyCharsText by remember(settings) {
        mutableStateOf(
            settings.getInt("chat_history_max_chars", ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS)
                .coerceIn(4_000, 100_000).toString(),
        )
    }
    var usageSummary by remember { mutableStateOf("Token 用量：本轮尚无服务端 usage 数据") }
    var selectedEmbeddingDimensions by remember(context) {
        mutableStateOf(
            context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
                .getInt("embedding_output_dimensions", 768)
                .takeIf { it in CHAT_EMBEDDING_DIMENSION_OPTIONS } ?: 768,
        )
    }
    var dimensionMenuExpanded by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<ChatLine>() }
    val listState = rememberLazyListState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val historyStore = remember(context, memoryScopeId) {
        val legacyStore = ChatHistoryStore(
            context.applicationContext,
            MemoryScopeFileNames.forScope(ChatHistoryStore.DEFAULT_FILE_NAME, memoryScopeId),
        )
        SqliteChatHistoryRepository(
            context.applicationContext,
            memoryScopeId,
            legacyStore,
        )
    }
    DisposableEffect(historyStore) {
        onDispose { historyStore.close() }
    }
    val memoryCredentialStore = remember(context) { AndroidKeystoreMemoryCredentialStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    val historySaveMutex = remember { Mutex() }
    val historySaveRevision = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val diagnosticStore = remember(context) { DiagnosticLogStore(context.applicationContext.filesDir) }
    val activeWorldbookId = remember(settings) {
        settings.getString("active_worldbook_id", "") ?: ""
    }
    var localWorldbookEntries by remember(context) {
        mutableStateOf<List<com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry>>(emptyList())
    }
    var worldbooksReady by remember { mutableStateOf(false) }
    val worldbookStateKey = "worldbook_activation_state_${activeMemoryRoleId}_${memoryConversationId}_${activeWorldbookId.ifBlank { "none" }}"
    val worldbookActivationState = remember(memoryScopeId, activeMemoryRoleId, activeWorldbookId) {
        ChatWorldBookStateStore(settings, diagnosticStore).restore(worldbookStateKey)
    }
    var characterCards by remember(context) { mutableStateOf(emptyList<com.mistbell.tavern.v3.feature.character.StoredCharacterCard>()) }
    var characterMenuExpanded by remember { mutableStateOf(false) }
    val selectedCharacter = characterCards.firstOrNull { it.id == selectedCharacterId }

    val libraryLoadingCoordinator = remember(context, diagnosticStore) {
        ChatLibraryLoadingCoordinator(context.applicationContext, diagnosticStore)
    }

    LaunchedEffect(context) {
        characterCards = withContext(Dispatchers.IO) {
            libraryLoadingCoordinator.loadCharacterCards()
        }
        if (selectedCharacterId.isNotBlank() && characterCards.none { it.id == selectedCharacterId }) {
            selectedCharacterId = ""
        }
    }

    LaunchedEffect(context, activeWorldbookId) {
        worldbooksReady = false
        localWorldbookEntries = withContext(Dispatchers.IO) {
            libraryLoadingCoordinator.loadWorldbookEntries(activeWorldbookId)
        }
        worldbooksReady = true
    }
    val memoryExtractionInFlight = remember { mutableSetOf<String>() }

    val historySaveCoordinator = remember(
        historyStore,
        coroutineScope,
        historySaveMutex,
        historySaveRevision,
        diagnosticStore,
    ) {
        ChatHistorySaveCoordinator(
            historyStore = historyStore,
            coroutineScope = coroutineScope,
            saveMutex = historySaveMutex,
            saveRevision = historySaveRevision,
            diagnosticStore = diagnosticStore,
        )
    }

    fun persistHistory() {
        val snapshot = history.map { line ->
            PersistedChatMessage(
                id = line.id,
                role = line.role,
                content = line.content,
                createdAtEpochMillis = line.createdAtEpochMillis,
                deliveryState = line.deliveryState,
            )
        }
        historySaveCoordinator.save(snapshot)
    }

    val importSessionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ChatSessionImportCoordinator(
                context = context,
                coroutineScope = coroutineScope,
                historyStore = historyStore,
                historySaveMutex = historySaveMutex,
                historySaveRevision = historySaveRevision,
                history = history,
                setStatus = { status = it },
            ).import(uri)
        }
    }
    if (showImportWarning) {
        AlertDialog(
            onDismissRequest = { showImportWarning = false },
            title = { Text("导入会覆盖当前会话") },
            text = {
                Text("导入的消息会替换当前会话的聊天记录，不会自动创建新会话。建议先导出当前会话备份。是否继续选择文件？")
            },
            confirmButton = {
                Button(onClick = {
                    showImportWarning = false
                    importSessionLauncher.launch(arrayOf("application/json", "application/jsonl", "text/plain", "*/*"))
                }) { Text("继续选择文件") }
            },
            dismissButton = {
                Button(onClick = { showImportWarning = false }) { Text("取消") }
            },
        )
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            ChatSessionExportCoordinator(context, coroutineScope) { status = it }
                .export(uri, memoryConversationId, history, jsonLines = false)
        }
    }
    val exportJsonlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/jsonl")) { uri ->
        if (uri != null) {
            ChatSessionExportCoordinator(context, coroutineScope) { status = it }
                .export(uri, memoryConversationId, history, jsonLines = true)
        }
    }

    val historyLoadCoordinator = remember(historyStore, diagnosticStore) {
        ChatHistoryLoadCoordinator(historyStore, diagnosticStore)
    }

    LaunchedEffect(historyStore) {
        // A role or conversation change must never leave the previous scope's messages on screen.
        historyReady = false
        historyLoadFailed = false
        history.clear()
        when (
            historyLoadCoordinator.load(
                onLoaded = { messages, recoveredInterruptedReply ->
                    history.clear()
                    history.addAll(messages.map { message ->
                        ChatLine(
                            id = message.id,
                            role = message.role,
                            content = message.content,
                            createdAtEpochMillis = message.createdAtEpochMillis,
                            deliveryState = message.deliveryState,
                        )
                    })
                    if (recoveredInterruptedReply) {
                        status = "已恢复聊天记录；上次未完成的回复已标记为失败，可继续发送新消息。"
                    }
                },
                onRecoverySaveFailed = {
                    status = "已恢复未完成回复，但恢复状态暂未保存；请导出诊断日志并稍后重试。"
                },
            )
        ) {
            ChatHistoryLoadResult.Missing -> Unit
            is ChatHistoryLoadResult.Loaded -> Unit
            ChatHistoryLoadResult.Corrupt -> {
                historyLoadFailed = true
                diagnosticStore.warn("chat.history.load_failed", "reason=corrupt_or_unsupported_format")
                status = "聊天记录文件损坏或版本不兼容；为避免覆盖原文件，未自动清空记录。"
            }
            ChatHistoryLoadResult.Unavailable -> {
                historyLoadFailed = true
                diagnosticStore.warn("chat.history.load_failed", "reason=temporarily_unavailable")
                status = "聊天记录暂时无法读取；原文件未修改，请稍后重试。"
            }
        }
        historyReady = true
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

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            ChatDiagnosticExportCoordinator(
                context = context,
                coroutineScope = coroutineScope,
                diagnosticStore = diagnosticStore,
                setStatus = { status = it },
            ).export(uri)
        }
    }

    fun scheduleMemoryIndexRebuild(
        records: List<com.mistbell.tavern.v3.core.memory.MemoryTextRecord>,
        runtime: com.mistbell.tavern.v3.core.memory.PassageEmbeddingRuntime,
        memoryTextStore: AtomicMemoryTextStore,
        vectorSnapshotStore: AtomicVectorSnapshotStore,
        reason: String,
    ) {
        ChatMemoryIndexRebuildCoordinator(
            coroutineScope = coroutineScope,
            diagnosticStore = diagnosticStore,
            memoryScopeId = memoryScopeId,
            isInFlight = { memoryIndexRebuildInFlight },
            setInFlight = { memoryIndexRebuildInFlight = it },
            isBusy = { busy },
            setStatus = { status = it },
        ).schedule(records, runtime, memoryTextStore, vectorSnapshotStore, reason)
    }

    fun scheduleMemoryExtractionIfDue(providers: ProviderProfileSet) {
        ChatMemoryExtractionSchedulingCoordinator(
            context = context,
            memoryCredentialStore = memoryCredentialStore,
            historyStore = historyStore,
            historySaveMutex = historySaveMutex,
            coroutineScope = coroutineScope,
            diagnosticStore = diagnosticStore,
            memoryScopeId = memoryScopeId,
            setStatus = { status = it },
        ).schedule(providers, history.toList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mistbell Tavern · 聊天测试") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("返回首页") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChatCharacterSelectionSection(
                        selectedCharacterName = selectedCharacter?.card?.name,
                        hasSelectedCharacter = selectedCharacter != null,
                        characterCards = characterCards,
                        menuExpanded = characterMenuExpanded,
                        onMenuExpandedChange = { characterMenuExpanded = it },
                        onCharacterSelected = { characterId ->
                            selectedCharacterId = characterId
                            if (characterId.isBlank()) {
                                settings.edit().remove("active_character_id").apply()
                            } else {
                                settings.edit().putString("active_character_id", characterId).apply()
                            }
                            characterMenuExpanded = false
                        },
                    )
                    ChatGenerationSettingsSection(
                        settings = settings,
                        maxTokensText = maxTokensText,
                        usageSummary = usageSummary,
                        onMaxTokensTextChange = { maxTokensText = it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = historyReady && !busy,
                            onClick = { showImportWarning = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("导入 JSON/JSONL") }
                        Button(
                            enabled = historyReady && !busy,
                            onClick = { exportJsonLauncher.launch("mistbell-session.json") },
                            modifier = Modifier.weight(1f),
                        ) { Text("导出 JSON") }
                        Button(
                            enabled = historyReady && !busy,
                            onClick = { exportJsonlLauncher.launch("mistbell-session.jsonl") },
                            modifier = Modifier.weight(1f),
                        ) { Text("导出 JSONL") }
                    }
                    ChatPrimaryApiSettingsSection(
                        settings = settings,
                        baseUrl = baseUrl,
                        modelId = modelId,
                        apiKey = apiKey,
                        onBaseUrlChange = { baseUrl = it },
                        onModelIdChange = { modelId = it },
                        onApiKeyChange = { apiKey = it },
                    )
                }
            }

            ChatHistoryBudgetSettingsSection(
                settings = settings,
                values = ChatHistoryBudgetSettingsValues(
                    recentTurnsText = recentTurnsText,
                    summaryCharsText = summaryCharsText,
                    historyCharsText = historyCharsText,
                ),
                callbacks = ChatHistoryBudgetSettingsCallbacks(
                    onRecentTurnsTextChange = { recentTurnsText = it },
                    onSummaryCharsTextChange = { summaryCharsText = it },
                    onHistoryCharsTextChange = { historyCharsText = it },
                ),
            )

            ChatMemoryApiSettingsSection(
                settings = settings,
                baseUrl = memoryBaseUrl,
                modelId = memoryModelId,
                apiKey = memoryApiKey,
                hasSavedCredential = hasSavedMemoryCredential,
                credentialStore = memoryCredentialStore,
                diagnosticStore = diagnosticStore,
                onBaseUrlChange = { memoryBaseUrl = it },
                onModelIdChange = { memoryModelId = it },
                onApiKeyChange = { memoryApiKey = it },
                onSavedCredentialChange = { hasSavedMemoryCredential = it },
                onStatusChange = { status = it },
            )

            ChatEmbeddingDimensionsSettingsSection(
                selectedDimensions = selectedEmbeddingDimensions,
                menuExpanded = dimensionMenuExpanded,
                onMenuExpandedChange = { dimensionMenuExpanded = it },
                onDimensionsSelected = { dimensions ->
                    selectedEmbeddingDimensions = dimensions
                    context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
                        .edit().putInt("embedding_output_dimensions", dimensions).apply()
                    dimensionMenuExpanded = false
                    status = if (dimensions <= 768) {
                        "已选择 $dimensions 维输出。切换档位后，已有向量需要按新维度重建。"
                    } else {
                        "已保留 $dimensions 维档位供更高维模型使用；当前 EmbeddingGemma 2 原生最高为 768 维，超出时会跳过本地记忆并继续普通聊天。"
                    }
                    diagnosticStore.info("embedding.dimensions.changed", "dimensions=$dimensions")
                },
            )
            Text(status, style = MaterialTheme.typography.bodySmall)
            ChatHistoryMessageList(
                history = history,
                busy = busy,
                listState = listState,
            )
            OutlinedTextField(
                value = chatUiState.draft,
                onValueChange = chatViewModel::updateDraft,
                label = { Text("输入消息") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !historyLoadFailed,
                minLines = 2,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val userText = chatViewModel.uiState.value.draft.trim()
                        if (userText.isEmpty() || busy) return@Button
                        if (!worldbooksReady) {
                            status = "世界书加载中，请稍后再发送。"
                            return@Button
                        }
                        if (modelId.isBlank()) {
                            status = "请先填写模型 ID。"
                            return@Button
                        }
                        val requestClient = ChatRequestClientCoordinator(
                            memoryCredentialStore = memoryCredentialStore,
                            settings = settings,
                            setStatus = { status = it },
                        ).create(
                            baseUrl = baseUrl,
                            modelId = modelId,
                            apiKey = apiKey,
                            memoryBaseUrl = memoryBaseUrl,
                            memoryModelId = memoryModelId,
                            memoryApiKey = memoryApiKey,
                        ) ?: return@Button
                        val providersForRequest = requestClient.providers
                        val client = requestClient.client
                        val openingMessage = characterCards.firstOrNull { it.id == selectedCharacterId }
                            ?.card?.firstMessage?.trim().orEmpty()
                        ChatTurnHistoryCoordinator().appendPendingTurn(history, openingMessage, userText)
                        persistHistory()
                        chatViewModel.clearDraft()
                        busy = true
                        memoryStatusNotice = ""
                        status = "正在连接聊天 API……"
                        val selectedCard = characterCards.firstOrNull { it.id == selectedCharacterId }?.card
                        val preparation = ChatSendMessageCoordinator(
                            settings = settings,
                            diagnosticStore = diagnosticStore,
                        ).prepare(
                            history = history,
                            userText = userText,
                            selectedCard = selectedCard,
                            characterDefinition = selectedCard?.let(::formatCharacterCardForPrompt),
                            localEntries = localWorldbookEntries,
                            worldbookState = worldbookActivationState,
                            worldbookStateKey = worldbookStateKey,
                            onBudgetExceeded = { droppedCount ->
                                mainHandler.post {
                                    status = "世界书预算超限：本轮有 ${droppedCount} 个条目未注入"
                                }
                            },
                        )
                        val messages = preparation.messages
                        val observer = ChatStreamObserverCoordinator(
                            mainHandler = mainHandler,
                            history = history,
                            diagnosticStore = diagnosticStore,
                            setBusy = { busy = it },
                            setStatus = { status = it },
                            memoryStatusNotice = { memoryStatusNotice },
                            setUsageSummary = { usageSummary = it },
                            persistHistory = ::persistHistory,
                            scheduleMemoryExtraction = ::scheduleMemoryExtractionIfDue,
                        ).create(providersForRequest)
                        coroutineScope.launch {
                            try {
                                ChatMemoryRuntimeCoordinator(
                                    context = context,
                                    mainHandler = mainHandler,
                                    diagnosticStore = diagnosticStore,
                                    memoryScopeId = memoryScopeId,
                                    memoryConversationId = memoryConversationId,
                                    indexRebuildCoordinator = ChatMemoryIndexRebuildCoordinator(
                                        coroutineScope = coroutineScope,
                                        diagnosticStore = diagnosticStore,
                                        memoryScopeId = memoryScopeId,
                                        isInFlight = { memoryIndexRebuildInFlight },
                                        setInFlight = { memoryIndexRebuildInFlight = it },
                                        isBusy = { busy },
                                        setStatus = { status = it },
                                    ),
                                    setStatus = { status = it },
                                    setMemoryStatusNotice = { memoryStatusNotice = it },
                                ).stream(client, messages, observer, maxTokensText)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                diagnosticStore.warn(
                                    "chat.embedding.fallback",
                                    "reason=${error::class.java.simpleName}",
                                )
                                status = "本地记忆不可用，已尝试回退普通聊天：${error.message?.take(120) ?: error::class.java.simpleName}"
                                try {
                                    client.stream(messages, observer, ChatGenerationOptions(maxTokens = maxTokensText.toIntOrNull()?.takeIf { it in 1..200_000 } ?: 4096))
                                } catch (chatCancelled: CancellationException) {
                                    throw chatCancelled
                                } catch (chatError: Exception) {
                                    busy = false
                                    status = chatError.message?.take(180) ?: "聊天请求配置失败。"
                                    val last = history.lastOrNull()
                                    if (last?.role == "assistant") {
                                        history[history.lastIndex] = last.copy(
                                            content = last.content.ifBlank { status },
                                            deliveryState = PersistedChatMessage.DeliveryState.FAILED,
                                        )
                                    }
                                    persistHistory()
                                }
                            }
                        }
                    },
                    enabled = !busy && historyReady && !historyLoadFailed && worldbooksReady,
                ) {
                    Text(
                        when {
                            !historyReady -> "恢复记录中…"
                            historyLoadFailed -> "记录异常，请先新建会话"
                            !worldbooksReady -> "加载世界书中…"
                            busy -> "生成中…"
                            else -> "发送消息"
                        },
                    )
                }
                Button(
                    onClick = {
                        val replacingUnreadableHistory = historyLoadFailed
                        history.clear()
                        historyLoadFailed = false
                        val previousMemoryScopeId = memoryScopeId
                        val newConversationId = UUID.randomUUID().toString()
                        memoryConversationId = newConversationId
                        ChatScopeSwitchCoordinator(
                            context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE),
                        ).persist(previousMemoryScopeId, newConversationId)
                        // The new scope gets its own history file. Do not overwrite the
                        // previous scope's persisted conversation with an empty list.
                        status = if (replacingUnreadableHistory) {
                            "已放弃无法读取的旧聊天记录并新建会话；新会话使用独立记忆索引，旧会话记忆文件保留但不会自动注入。"
                        } else {
                            "会话已清空并切换到独立记忆范围；旧会话记忆文件仍保留，但不会注入新会话。"
                        }
                    },
                    enabled = !busy && historyReady && (history.isNotEmpty() || historyLoadFailed),
                ) { Text(if (historyLoadFailed) "放弃旧记录并新建会话" else "清空会话") }
                Button(
                    onClick = {
                        exportLogLauncher.launch("mistbell-diagnostics.txt")
                    },
                    enabled = !busy && historyReady,
                ) { Text("导出诊断日志") }
            }
        }
    }
}
