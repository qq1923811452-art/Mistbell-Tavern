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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

private val CHAT_EMBEDDING_DIMENSION_OPTIONS = listOf(128, 256, 512, 768, 1024, 1536, 2048)

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

    LaunchedEffect(context) {
        characterCards = withContext(Dispatchers.IO) {
            ChatCharacterCardLibraryLoader(context.applicationContext).load()
        }
        if (selectedCharacterId.isNotBlank() && characterCards.none { it.id == selectedCharacterId }) {
            selectedCharacterId = ""
        }
    }

    LaunchedEffect(context, activeWorldbookId) {
        worldbooksReady = false
        val loadedWorldbookEntries = withContext(Dispatchers.IO) {
            ChatWorldBookLibraryLoader(context.applicationContext, diagnosticStore).load(activeWorldbookId)
        }
        localWorldbookEntries = loadedWorldbookEntries.getOrElse { error ->
            diagnosticStore.warn(
                "worldbook.load_failed",
                "book_id=$activeWorldbookId reason=${error::class.java.simpleName}",
            )
            emptyList()
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
            coroutineScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use(diagnosticStore::exportTo)
                            ?: error("无法创建日志文件。")
                    }
                }
                status = if (result.isSuccess) {
                    "诊断日志已导出。日志不主动包含聊天正文或 API 密钥。"
                } else {
                    "诊断日志导出失败：${result.exceptionOrNull()?.message?.take(100) ?: "文件写入失败"}"
                }
            }
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
                    Text("聊天 API 配置", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前角色：${selectedCharacter?.card?.name ?: "默认助手"}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (selectedCharacter == null) "尚未绑定角色卡；本轮使用默认系统提示。" else "角色卡会注入描述、性格、场景、开场白、示例对话及系统指令。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { characterMenuExpanded = true }) { Text("选择角色") }
                        DropdownMenu(expanded = characterMenuExpanded, onDismissRequest = { characterMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("默认助手") },
                                onClick = {
                                    selectedCharacterId = ""
                                    settings.edit().remove("active_character_id").apply()
                                    characterMenuExpanded = false
                                },
                            )
                            characterCards.forEach { stored ->
                                DropdownMenuItem(
                                    text = { Text(stored.card.name) },
                                    onClick = {
                                        selectedCharacterId = stored.id
                                        settings.edit().putString("active_character_id", stored.id).apply()
                                        characterMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = maxTokensText,
                        onValueChange = { value ->
                            maxTokensText = value.filter(Char::isDigit).take(6)
                            maxTokensText.toIntOrNull()?.takeIf { it in 1..200_000 }?.let { limit ->
                                settings.edit().putInt("chat_max_tokens", limit).apply()
                            }
                        },
                        label = { Text("最大生成 Token（max_tokens，1–200000）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(usageSummary, style = MaterialTheme.typography.bodySmall)
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
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            settings.edit().putString("chat_api_base_url", it).apply()
                        },
                        label = { Text("API Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = {
                            modelId = it
                            settings.edit().putString("chat_model_id", it).apply()
                        },
                        label = { Text("模型 ID（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key（远程接口必填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        "聊天 API Key 仅保存在当前页面内存中，离开页面后需要重新输入；独立记忆 API Key 可选择加密保存到 Android Keystore。Base URL 和模型 ID 会保存在本机设置中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("上下文历史预算", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = recentTurnsText,
                        onValueChange = { value ->
                            recentTurnsText = value.filter(Char::isDigit).take(3)
                            recentTurnsText.toIntOrNull()?.takeIf { it in 1..100 }?.let { turns ->
                                settings.edit().putInt("chat_history_recent_turns", turns).apply()
                            }
                        },
                        label = { Text("最近历史轮数（1–100）") },
                        supportingText = { Text("按用户消息计轮；同一轮中的多条助手消息会一起保留。") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = summaryCharsText,
                        onValueChange = { value ->
                            summaryCharsText = value.filter(Char::isDigit).take(5)
                            summaryCharsText.toIntOrNull()?.takeIf { it in 0..10_000 }?.let { chars ->
                                settings.edit().putInt("chat_history_summary_max_chars", chars).apply()
                            }
                        },
                        label = { Text("较早历史摘要上限（0–10000 字符）") },
                        supportingText = { Text("0 表示不注入较早历史摘要；摘要为本地提取式短句，不会修改原始聊天记录。") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = historyCharsText,
                        onValueChange = { value ->
                            historyCharsText = value.filter(Char::isDigit).take(6)
                            historyCharsText.toIntOrNull()?.takeIf { it in 12_000..100_000 }?.let { chars ->
                                settings.edit().putInt("chat_history_max_chars", chars).apply()
                            }
                        },
                        label = { Text("最近历史字符预算（4000–100000）") },
                        supportingText = { Text("默认 24000 字符。字符数不等于 Token 数；保留完整消息，不截断长回复，单条最新消息超限时仍会整条保留。") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "修改后从下一条消息生效。历史轮数、摘要和字符预算分别限制不同部分；字符数不等于模型 Token 数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("独立记忆整理 API（可选）", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = memoryBaseUrl,
                        onValueChange = {
                            memoryBaseUrl = it
                            settings.edit().putString("memory_api_base_url", it).apply()
                        },
                        label = { Text("记忆整理 API Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = memoryModelId,
                        onValueChange = {
                            memoryModelId = it
                            settings.edit().putString("memory_model_id", it).apply()
                        },
                        label = { Text("记忆整理模型 ID（留空则关闭）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = memoryApiKey,
                        onValueChange = { memoryApiKey = it },
                        label = { Text("独立记忆 API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (memoryApiKey.isBlank()) {
                                    status = "请先输入记忆整理 API Key，再保存到 Android Keystore。"
                                } else {
                                    runCatching {
                                        val newHandle = memoryCredentialStore.put(memoryApiKey)
                                        val oldHandle = settings.getString("memory_api_credential_handle", null)
                                        if (!settings.edit().putString("memory_api_credential_handle", newHandle).commit()) {
                                            memoryCredentialStore.delete(newHandle)
                                            error("Credential handle could not be saved.")
                                        }
                                        if (!oldHandle.isNullOrBlank() && oldHandle != newHandle) {
                                            memoryCredentialStore.delete(oldHandle)
                                        }
                                        memoryApiKey = ""
                                        hasSavedMemoryCredential = true
                                        status = "记忆整理 API Key 已加密保存；设置中只保存凭据句柄。"
                                        diagnosticStore.info("memory.credential.saved")
                                    }.onFailure { error ->
                                        status = "记忆 API Key 保存失败：${error::class.java.simpleName}"
                                        diagnosticStore.warn("memory.credential.save_failed", "reason=${error::class.java.simpleName}")
                                    }
                                }
                            },
                        ) { Text("安全保存密钥") }
                        Button(
                            onClick = {
                                val handle = settings.getString("memory_api_credential_handle", null)
                                if (!handle.isNullOrBlank()) memoryCredentialStore.delete(handle)
                                settings.edit().remove("memory_api_credential_handle").commit()
                                hasSavedMemoryCredential = false
                                status = "已删除保存的记忆 API 密钥。"
                                diagnosticStore.info("memory.credential.deleted")
                            },
                            enabled = hasSavedMemoryCredential,
                        ) { Text("删除已保存密钥") }
                    }
                    Text(
                        "与聊天 API 分开配置。Base URL 和模型 ID 保存在本机；密钥可仅本次使用，或通过 Android Keystore 加密保存。每累计 3 轮已完成对话后异步整理，失败不会撤销聊天回复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (hasSavedMemoryCredential) "已保存加密密钥；不会显示或回填明文。" else "尚未保存记忆 API 密钥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dimensionMenuExpanded = true }) {
                    Text("向量维度：${selectedEmbeddingDimensions} 维")
                }
                DropdownMenu(
                    expanded = dimensionMenuExpanded,
                    onDismissRequest = { dimensionMenuExpanded = false },
                ) {
                    CHAT_EMBEDDING_DIMENSION_OPTIONS.forEach { dimensions ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (dimensions <= 768) "$dimensions 维"
                                    else "$dimensions 维（当前模型不支持）",
                                )
                            },
                            onClick = {
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
                    }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(history) { _, line ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                if (line.role == "user") "你" else "Mistbell",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                line.content.ifBlank {
                                    when {
                                        busy && line.role == "assistant" -> "正在生成……"
                                        line.deliveryState == PersistedChatMessage.DeliveryState.PENDING ->
                                            "上次回复未完成，可重新发送。"
                                        line.deliveryState == PersistedChatMessage.DeliveryState.FAILED &&
                                            line.role == "assistant" ->
                                            "上次回复未完成，未生成完整回答。"
                                        else -> ""
                                    }
                                },
                            )
                        }
                    }
                }
            }
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
                        val completeHistory = history.dropLast(2).mapNotNull { line ->
                            if (line.content.isBlank()) {
                                null
                            } else {
                                PromptMessage(
                                    if (line.role == "user") PromptRole.USER else PromptRole.ASSISTANT,
                                    line.content,
                                )
                            }
                        }
                        val contextWindow = ChatPromptHistoryCoordinator(settings).build(completeHistory)
                        // Activate entries from the asynchronously loaded local library against
                        // the current turn plus a bounded tail of recent conversation.
                        val selectedCard = characterCards.firstOrNull { it.id == selectedCharacterId }?.card
                        val characterDefinition = selectedCard?.let(::formatCharacterCardForPrompt)
                        val activationReport = ChatWorldBookActivationCoordinator(
                            settings = settings,
                            diagnosticStore = diagnosticStore,
                        ).activate(
                            localEntries = localWorldbookEntries,
                            selectedCard = selectedCard,
                            scanText = userText,
                            recentTexts = completeHistory.takeLast(6).map { it.content },
                            state = worldbookActivationState,
                            stateKey = worldbookStateKey,
                            onBudgetExceeded = { droppedCount ->
                                mainHandler.post {
                                    status = "世界书预算超限：本轮有 ${droppedCount} 个条目未注入"
                                }
                            },
                        )
                        val activatedWorldbook = activationReport.activatedEntries
                        val messages = ChatPromptAssemblyCoordinator().assemble(
                            characterDefinition = characterDefinition,
                            contextWindow = contextWindow,
                            activatedWorldbook = activatedWorldbook.map { it.entry },
                            latestUserMessage = userText,
                        )
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
                                    status = if (installedModel == null) {
                                        "本地模型未安装或校验失败，继续普通聊天；向量记忆暂不可用。"
                                    } else {
                                        "当前 EmbeddingGemma 2 原生最高支持 768 维；所选 ${configuredDimensions} 维暂不可用于本地记忆，继续普通聊天。"
                                    }
                                    client.stream(messages, observer, ChatGenerationOptions(maxTokens = maxTokensText.toIntOrNull()?.takeIf { it in 1..200_000 } ?: 4096))
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
                                                        scheduleMemoryIndexRebuild(
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
                                                        scheduleMemoryIndexRebuild(
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
                                    status = "本地记忆检索已接入；后端=${backendStatus.backend}，证据=${backendStatus.backendEvidence}。配置状态不等于芯片级 NPU 实测。" +
                                        if (indexMaintenanceMessage.isBlank()) "" else " $indexMaintenanceMessage"
                                    MemoryAwareChatRequest(
                                        chatClient = client,
                                        memoryContext = memoryContext,
                                        onMemoryStatus = { notice ->
                                            mainHandler.post {
                                                memoryStatusNotice = notice
                                                status = notice
                                            }
                                        },
                                    ).stream(
                                        messages,
                                        observer,
                                        ChatGenerationOptions(
                                            maxTokens = maxTokensText.toIntOrNull()?.takeIf { it in 1..200_000 } ?: 4096,
                                        ),
                                    )
                                    }
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
