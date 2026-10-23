@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.mistbell.tavern.v3.feature.chat

import android.os.Handler
import android.util.AtomicFile
import android.os.Looper
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
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
import com.mistbell.tavern.v3.core.security.ChatCredentialStore
import com.mistbell.tavern.v3.core.settings.getIntCompat
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.feature.memory.MemoryCredentialResolver
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionClient
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
import com.mistbell.tavern.v3.feature.sessions.ChatSession
import com.mistbell.tavern.v3.feature.sessions.SessionKind
import com.mistbell.tavern.v3.feature.sessions.SessionParticipant
import com.mistbell.tavern.v3.feature.sessions.SessionStore
import com.mistbell.tavern.v3.feature.sessions.ui.GroupCandidate
import com.mistbell.tavern.v3.feature.sessions.ui.SessionListScreen
import com.mistbell.tavern.v3.feature.worldbook.WorldBookLibraryStore
import com.mistbell.tavern.v3.ui.findActivity



internal val CHAT_EMBEDDING_DIMENSION_OPTIONS = listOf(128, 256, 512, 768, 1024, 1536, 2048)

/**
 * Minimal end-to-end chat screen for 3.0 development.
 * Provider settings and the API key remain in screen memory only; they are not persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    settingsMode: Boolean = false,
) {
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
    val chatGenerationPreferences = remember(context) {
        ChatGenerationPreferences(context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE))
    }
    val chatCredentialStore = remember(context) { ChatCredentialStore(context) }
    var apiKey by remember {
        mutableStateOf(
            chatGenerationPreferences.credentialHandle
                ?.let { handle -> chatCredentialStore.resolve(handle) }
                .orEmpty(),
        )
    }
    var streamEnabled by remember { mutableStateOf(chatGenerationPreferences.streamEnabled) }
    var thinkingEnabled by remember { mutableStateOf(chatGenerationPreferences.thinkingEnabled) }
    var hasSavedChatCredential by remember {
        mutableStateOf(
            chatGenerationPreferences.credentialHandle?.let(chatCredentialStore::contains) == true,
        )
    }
    var chatSettingsStatus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val handle = chatGenerationPreferences.credentialHandle ?: return@LaunchedEffect
        if (!chatCredentialStore.contains(handle)) {
            chatGenerationPreferences.setCredentialHandle(null)
            hasSavedChatCredential = false
            apiKey = ""
            chatSettingsStatus = "已保存的密钥已失效，请重新输入"
        }
    }
    var busy by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("") }
    val backupCoroutineScope = rememberCoroutineScope()
    val backupCoordinator = remember(context) {
        ChatBackupCoordinator(context, backupCoroutineScope, { backupStatus = it }) {
            context.findActivity()?.recreate()
        }
    }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) backupCoordinator.export(uri) }
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null && !busy) backupCoordinator.restore(uri) }
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
    var historyReady by remember { mutableStateOf(false) }
    var historyLoadFailed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("填写模型 ID 和 API 配置后即可测试聊天。") }
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
    val sessionStore = remember(context) { SessionStore(context) }
    var sessions by remember { mutableStateOf(emptyList<ChatSession>()) }
    var showSessionList by remember(settingsMode) { mutableStateOf(!settingsMode) }
    var lastGroupSpeakerId by remember { mutableStateOf<String?>(null) }
    var lastPromptMessages by remember { mutableStateOf<List<PromptMessage>>(emptyList()) }
    var memoryExtractionEnabled by remember {
        mutableStateOf(settings.getBoolean("memory_extraction_enabled", true))
    }
    var showPromptPreview by remember { mutableStateOf(false) }
    var showSessionSettings by remember { mutableStateOf(false) }
    LaunchedEffect(sessionStore) {
        val current = memoryConversationId
        val existing = sessionStore.list()
        if (existing.none { it.id == current }) {
            runCatching { sessionStore.create(activeMemoryRoleId, "新对话", id = current) }
        }
        sessions = sessionStore.list()
    }
    var memoryIndexRebuildInFlight by remember { mutableStateOf(false) }
    var memoryStatusNotice by remember { mutableStateOf("") }
    var maxTokensText by remember(settings) {
        mutableStateOf(settings.getInt("chat_max_tokens", 4096).coerceIn(1, 200_000).toString())
    }
    var recentTurnsText by remember(settings) {
        mutableStateOf(
            settings.getIntCompat("chat_history_recent_turns", ConversationContextWindowBuilder.DEFAULT_RECENT_TURNS)
                .coerceIn(1, 100).toString(),
        )
    }
    var summaryCharsText by remember(settings) {
        mutableStateOf(
            settings.getIntCompat(
                "chat_history_summary_max_chars",
                ConversationContextWindowBuilder.DEFAULT_SUMMARY_MAX_CHARS,
            ).coerceIn(0, 10_000).toString(),
        )
    }
    var historyCharsText by remember(settings) {
        mutableStateOf(
            settings.getIntCompat("chat_history_max_chars", ConversationContextWindowBuilder.DEFAULT_MAX_HISTORY_CHARS)
                .coerceIn(4_000, 2_000_000).toString(),
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
    val historyScopeId = memoryConversationId.ifBlank { memoryScopeId }
    val historyStore = remember(context, historyScopeId) {
        val legacyStore = ChatHistoryStore(
            context.applicationContext,
            MemoryScopeFileNames.forScope(ChatHistoryStore.DEFAULT_FILE_NAME, historyScopeId),
        )
        SqliteChatHistoryRepository(
            context.applicationContext,
            historyScopeId,
            legacyStore,
            legacyScopeId = memoryScopeId.takeIf { it.isNotBlank() && it != historyScopeId },
            legacyFileStore = memoryScopeId
                .takeIf { it.isNotBlank() && it != historyScopeId }
                ?.let { scope ->
                    ChatHistoryStore(
                        context.applicationContext,
                        MemoryScopeFileNames.forScope(ChatHistoryStore.DEFAULT_FILE_NAME, scope),
                    )
                },
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
    val worldbookLibrary = remember(context) {
        WorldBookLibraryStore(File(context.filesDir, "worldbooks")).list()
    }
    val sessionWorldbookId = sessions.firstOrNull { it.id == memoryConversationId }
        ?.worldbookId.orEmpty()
    val sessionWorldbookExists = worldbookLibrary.any { it.id == sessionWorldbookId }
    val effectiveWorldbookId = when {
        sessionWorldbookId.isNotBlank() && sessionWorldbookExists -> sessionWorldbookId
        else -> activeWorldbookId
    }
    val worldbookSession = rememberChatWorldBookSessionState(
        context = context,
        settings = settings,
        diagnosticStore = diagnosticStore,
        roleId = activeMemoryRoleId,
        conversationId = memoryConversationId,
        worldbookId = effectiveWorldbookId,
    )
    var characterCards by remember(context) { mutableStateOf(emptyList<com.mistbell.tavern.v3.feature.character.StoredCharacterCard>()) }
    var characterMenuExpanded by remember { mutableStateOf(false) }
    val selectedCharacter = characterCards.firstOrNull { it.id == selectedCharacterId }

    val libraryLoadingCoordinator = remember(context, diagnosticStore) {
        ChatLibraryLoadingCoordinator(context.applicationContext, diagnosticStore)
    }

    ChatLibraryLoadingLifecycle(
        context = context,
        coordinator = libraryLoadingCoordinator,
        selectedCharacterId = selectedCharacterId,
        setSelectedCharacterId = { selectedCharacterId = it },
        setCharacterCards = { characterCards = it },
    )
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
        val startedNanos = System.nanoTime()
        val snapshot = ChatHistorySnapshotFactory.create(history)
        historySaveCoordinator.save(snapshot)
        val preview = snapshot.lastOrNull { it.content.isNotBlank() }
            ?.content
            ?.trim()
            ?.replace('\n', ' ')
            ?.take(80)
            .orEmpty()
        if (memoryConversationId.isNotBlank() && preview.isNotEmpty()) {
            if (sessionStore.touch(memoryConversationId, preview, snapshot.size)) {
                sessions = sessionStore.list()
            }
        }
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        diagnosticStore.info(
            "chat.history.scheduled",
            "messages=${snapshot.size}; snapshotMs=$elapsedMillis",
        )
    }

    @Suppress("LongMethod")
    fun regenerateReply(targetMessageId: String) {
        val plan = RegeneratePlanner.plan(history.toList(), targetMessageId)
        if (plan == null) {
            status = "无法重新生成：找不到对应的用户消息。"
            return
        }
        val configuration = ChatRequestClientCoordinator(
            memoryCredentialStore = memoryCredentialStore,
            settings = settings,
            setStatus = { status = it },
        ).create(
            baseUrl = baseUrl,
            modelId = modelId,
            apiKey = apiKey,
            memoryBaseUrl = "",
            memoryModelId = "",
            memoryApiKey = "",
        ) ?: return
        val messages = ChatTurnPromptPreparationCoordinator(
            settings = settings,
            diagnosticStore = diagnosticStore,
            mainHandler = mainHandler,
            setStatus = { status = it },
        ).prepare(
            history = plan.prefixHistory,
            userText = plan.userText,
            characterCards = characterCards,
            selectedCharacterId = selectedCharacterId,
            localWorldbookEntries = worldbookSession.entries,
            worldbookState = worldbookSession.activation.activationState,
            worldbookStateKey = worldbookSession.activation.stateKey,
            historyIsComplete = true,
        )
        lastPromptMessages = messages
        busy = true
        status = "正在重新生成…"
        val generated = StringBuilder()
        val observer = object : ChatStreamObserver {
            override fun onDelta(text: String) {
                generated.append(text)
            }

            override fun onComplete() {
                mainHandler.post {
                    val index = history.indexOfFirst { it.id == targetMessageId }
                    val replacement = if (index >= 0) {
                        val line = history[index]
                        val base = line.variants.ifEmpty { listOf(line.content) }
                        val variants = MessageVariants.append(base, generated.toString())
                        if (variants !== base) {
                            history[index] = line.copy(
                                content = variants.last(),
                                activeVariant = variants.lastIndex,
                                variants = variants,
                            )
                            persistHistory()
                            diagnosticStore.info("chat.regenerate.completed", "variants=${variants.size}")
                        }
                        true
                    } else {
                        false
                    }
                    busy = false
                    status = if (replacement) "已生成新版本。" else "生成完成，但目标消息已不存在。"
                }
            }

            override fun onFailure(failure: ChatStreamFailure) {
                mainHandler.post {
                    busy = false
                    status = "重新生成失败：${failure.safeMessage}"
                }
            }
        }
        configuration.client.stream(
            messages,
            observer,
            ChatGenerationOptions(
                maxTokens = maxTokensText.toIntOrNull()?.takeIf { it in 1..200_000 } ?: 4096,
                stream = chatGenerationPreferences.streamEnabled,
                thinkingEnabled = chatGenerationPreferences.thinkingEnabled,
            ),
        )
    }

    val sessionDocumentActions = rememberChatSessionDocumentActions(
        context = context,
        coroutineScope = coroutineScope,
        historyStore = historyStore,
        historySaveMutex = historySaveMutex,
        historySaveRevision = historySaveRevision,
        history = history,
        conversationId = memoryConversationId,
        setStatus = { status = it },
        sessionTitle = sessions.firstOrNull { it.id == memoryConversationId }?.title
            ?: "Mistbell Tavern session",
        sessionKind = sessions.firstOrNull { it.id == memoryConversationId }?.kind
            ?: SessionKind.SINGLE,
        sessionParticipants = sessions.firstOrNull { it.id == memoryConversationId }?.participants
            .orEmpty(),
        onImported = { archive ->
            sessionStore.updateImportedMetadata(
                id = memoryConversationId,
                title = archive.title,
                kind = archive.kind,
                participants = archive.participants,
            )
            val preview = history.lastOrNull { it.content.isNotBlank() }
                ?.content
                ?.trim()
                ?.replace('\n', ' ')
                ?.take(80)
                .orEmpty()
            if (memoryConversationId.isNotBlank() && preview.isNotEmpty()) {
                if (sessionStore.touch(memoryConversationId, preview, history.size)) {
                    sessions = sessionStore.list()
                }
            }
        },
    )

    val historyLoadCoordinator = remember(historyStore, diagnosticStore) {
        ChatHistoryLoadCoordinator(historyStore, diagnosticStore)
    }

    ChatHistoryLoadLifecycle(
        context = context,
        historyStore = historyStore,
        historyLoadCoordinator = historyLoadCoordinator,
        diagnosticStore = diagnosticStore,
        history = history,
        setHistoryReady = { historyReady = it },
        setHistoryLoadFailed = { historyLoadFailed = it },
        setStatus = { status = it },
    )

    LaunchedEffect(historyReady, historyLoadFailed, selectedCharacterId, memoryConversationId) {
        if (!historyReady || historyLoadFailed || history.isNotEmpty()) return@LaunchedEffect
        val session = sessions.firstOrNull { it.id == memoryConversationId }
        if (session != null && session.kind != SessionKind.SINGLE) return@LaunchedEffect
        val card = characterCards.firstOrNull { it.id == selectedCharacterId }?.card
            ?: return@LaunchedEffect
        val greeting = card.firstMessage.trim()
        if (greeting.isEmpty()) return@LaunchedEffect
        val content = greeting.replace("{{char}}", card.name).replace("{{user}}", "你")
        history.add(
            ChatLine(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = content,
                createdAtEpochMillis = System.currentTimeMillis(),
                deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
                speakerId = selectedCharacterId.takeIf(String::isNotBlank),
                speakerName = card.name,
            ),
        )
        persistHistory()
        diagnosticStore.info("chat.greeting.inserted", "chars=${content.length}")
    }

    val memoryOperationsCoordinator = remember(
        context,
        memoryCredentialStore,
        historyStore,
        historySaveMutex,
        coroutineScope,
        diagnosticStore,
        memoryScopeId,
    ) {
        ChatMemoryOperationsCoordinator(
            context = context,
            memoryCredentialStore = memoryCredentialStore,
            historyStore = historyStore,
            historySaveMutex = historySaveMutex,
            coroutineScope = coroutineScope,
            diagnosticStore = diagnosticStore,
            memoryScopeId = memoryScopeId,
            isIndexRebuildInFlight = { memoryIndexRebuildInFlight },
            setIndexRebuildInFlight = { memoryIndexRebuildInFlight = it },
            isChatBusy = { busy },
            setStatus = { status = it },
        )
    }

    if (showSessionList) {
        SessionListScreen(
            sessions = sessions,
            selectedSessionId = memoryConversationId,
            onSessionClick = { id ->
                if (busy) {
                    status = "生成中，请先停止再切换会话。"
                    return@SessionListScreen
                }
                sessions.firstOrNull { it.id == id }?.roleId
                    ?.takeIf(String::isNotBlank)
                    ?.let { roleId -> selectedCharacterId = roleId }
                memoryConversationId = id
                settings.edit().putString("memory_extraction_conversation_id", id).apply()
                diagnosticStore.info("chat.session.switched", "reason=click")
                showSessionList = false
            },
            onCreateSession = {
                val created = runCatching { sessionStore.create(activeMemoryRoleId, "新对话") }.getOrNull()
                if (created != null) {
                    memoryConversationId = created.id
                    settings.edit().putString("memory_extraction_conversation_id", created.id).apply()
                    sessions = sessionStore.list()
                    diagnosticStore.info("chat.session.created", "sessions=${sessions.size}")
                }
                showSessionList = false
            },
            onRenameSession = { id, title ->
                if (sessionStore.rename(id, title)) {
                    sessions = sessionStore.list()
                    diagnosticStore.info("chat.session.renamed", "titleChars=${title.length}")
                }
            },
            onDeleteSession = { id ->
                runCatching {
                    val legacy = ChatHistoryStore(
                        context.applicationContext,
                        MemoryScopeFileNames.forScope(ChatHistoryStore.DEFAULT_FILE_NAME, id),
                    )
                    SqliteChatHistoryRepository(context.applicationContext, id, legacy).use { repository ->
                        repository.deleteAll()
                    }
                }.onFailure { error ->
                    diagnosticStore.warn("chat.session.history_delete_failed", error::class.java.simpleName)
                }
                sessionStore.delete(id)
                sessions = sessionStore.list()
                diagnosticStore.info("chat.session.deleted", "remaining=${sessions.size}")
                if (id == memoryConversationId) {
                    val next = sessions.firstOrNull()
                        ?: runCatching { sessionStore.create(activeMemoryRoleId, "新对话") }.getOrNull()
                            ?.also { created ->
                                sessions = sessionStore.list()
                            }
                    if (next != null) {
                        memoryConversationId = next.id
                        settings.edit().putString("memory_extraction_conversation_id", next.id).apply()
                        diagnosticStore.info("chat.session.switched", "reason=delete_fallback")
                    }
                }
            },
            onTogglePin = { id, pinned ->
                if (sessionStore.setPinned(id, pinned)) {
                    sessions = sessionStore.list()
                    diagnosticStore.info("chat.session.pinned", "pinned=$pinned")
                }
            },
            characters = characterCards.map { card ->
                GroupCandidate(card.id, card.card.name)
            },
            onCreateGroup = { ids ->
                val participants = ids.mapNotNull { id ->
                    characterCards.firstOrNull { it.id == id }?.let { card ->
                        SessionParticipant(card.id, card.card.name)
                    }
                }
                if (participants.size >= 2) {
                    val created = runCatching {
                        sessionStore.create(
                            roleId = activeMemoryRoleId,
                            title = "群聊",
                            kind = SessionKind.GROUP,
                            participants = participants,
                        )
                    }.getOrNull()
                    if (created != null) {
                        memoryConversationId = created.id
                        settings.edit().putString("memory_extraction_conversation_id", created.id).apply()
                        sessions = sessionStore.list()
                        diagnosticStore.info(
                            "chat.session.created",
                            "kind=group; participants=${participants.size}",
                        )
                    }
                }
                showSessionList = false
            },
        )
        return
    }

    if (showSessionSettings) {
        ChatSessionSettingsSheet(
            characters = characterCards,
            selectedCharacterId = selectedCharacterId,
            messageCount = history.size,
            worldbooks = worldbookLibrary.map { book -> book.id to book.name },
            selectedWorldbookId = sessionWorldbookId,
            onSelectWorldbook = { worldbookId ->
                if (memoryConversationId.isNotBlank()) {
                    if (sessionStore.setWorldbookId(memoryConversationId, worldbookId)) {
                        sessions = sessionStore.list()
                        status = "本会话世界书已更新。"
                    } else {
                        status = "世界书切换失败，请重试。"
                    }
                }
                showSessionSettings = false
            },
            onSelectCharacter = { characterId ->
                selectedCharacterId = characterId
                ChatCharacterSelectionCoordinator(settings).select(characterId)
                if (memoryConversationId.isNotBlank()) {
                    sessionStore.setRoleId(memoryConversationId, characterId)
                    sessions = sessionStore.list()
                }
                showSessionSettings = false
            },
            onClearSession = {
                history.clear()
                persistHistory()
                showSessionSettings = false
            },
            onImportSession = {
                showSessionSettings = false
                sessionDocumentActions.onImportSession()
            },
            onExportJson = {
                showSessionSettings = false
                sessionDocumentActions.onExportJson()
            },
            onExportJsonl = {
                showSessionSettings = false
                sessionDocumentActions.onExportJsonl()
            },
            onOpenGlobalSettings = {
                showSessionSettings = false
                onBack()
            },
            onDismiss = { showSessionSettings = false },
        )
    }

    if (showPromptPreview) {
        PromptPreviewSheet(
            messages = lastPromptMessages,
            usageSummary = usageSummary,
            onDismiss = { showPromptPreview = false },
        )
    }

    BackHandler(enabled = !showSessionList) {
        if (busy) {
            status = "生成中，请先停止再切换会话。"
        } else {
            showSessionList = true
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        TextButton(onClick = { characterMenuExpanded = true }) {
                            Text(
                                (selectedCharacter?.card?.name ?: "聊天") + " ▾",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        DropdownMenu(
                            expanded = characterMenuExpanded,
                            onDismissRequest = { characterMenuExpanded = false },
                        ) {
                            characterCards.forEach { character ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            character.card.name +
                                                if (character.id == selectedCharacterId) " ✓" else "",
                                        )
                                    },
                                    onClick = {
                                        selectedCharacterId = character.id
                                        ChatCharacterSelectionCoordinator(settings).select(character.id)
                                        if (memoryConversationId.isNotBlank() &&
                                            sessionStore.setRoleId(memoryConversationId, character.id)
                                        ) {
                                            sessions = sessionStore.list()
                                        }
                                        characterMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    Button(onClick = onBack) { Text("返回首页") }
                },
                actions = {
                    if (lastPromptMessages.isNotEmpty()) {
                        TextButton(onClick = { showPromptPreview = true }) { Text("☰") }
                    }
                    TextButton(
                        onClick = {
                            if (busy) {
                                status = "生成中，请先停止再切换会话。"
                            } else {
                                showSessionList = true
                            }
                        },
                    ) { Text("会话") }
                    TextButton(onClick = { showSessionSettings = true }) { Text("⚙") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (settingsMode) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ChatConfigurationPanel(
                settings = settings,
                selectedCharacterName = selectedCharacter?.card?.name,
                hasSelectedCharacter = selectedCharacter != null,
                characterCards = characterCards,
                characterMenuExpanded = characterMenuExpanded,
                onCharacterMenuExpandedChange = { characterMenuExpanded = it },
                onCharacterSelected = { characterId ->
                    selectedCharacterId = characterId
                    ChatCharacterSelectionCoordinator(settings).select(characterId)
                    characterMenuExpanded = false
                    if (memoryConversationId.isNotBlank() &&
                        sessionStore.setRoleId(memoryConversationId, characterId)
                    ) {
                        sessions = sessionStore.list()
                    }
                },
                maxTokensText = maxTokensText,
                usageSummary = usageSummary,
                onMaxTokensTextChange = { maxTokensText = it },
                transferEnabled = historyReady && !busy,
                onImportSession = sessionDocumentActions.onImportSession,
                onExportJson = sessionDocumentActions.onExportJson,
                onExportJsonl = sessionDocumentActions.onExportJsonl,
                baseUrl = baseUrl,
                modelId = modelId,
                apiKey = apiKey,
                onBaseUrlChange = { baseUrl = it },
                onModelIdChange = { modelId = it },
                onApiKeyChange = { apiKey = it },
                showCharacterSection = false,
            )

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

            ChatBackupSettingsSection(
                onExport = { exportBackupLauncher.launch("mistbell-backup.zip") },
                onRestore = {
                    if (!busy) {
                        restoreBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                },
                canRestore = !busy,
                statusMessage = backupStatus,
            )

            ChatGenerationSwitchSection(
                hasSavedCredential = hasSavedChatCredential,
                canSaveCredential = apiKey.isNotBlank(),
                streamEnabled = streamEnabled,
                thinkingEnabled = thinkingEnabled,
                statusMessage = chatSettingsStatus,
                onSaveCredential = {
                    if (apiKey.isBlank()) {
                        chatSettingsStatus = "请先输入 API Key"
                    } else {
                        val handle = runCatching { chatCredentialStore.save(apiKey) }.getOrNull()
                        if (handle != null) {
                            chatGenerationPreferences.setCredentialHandle(handle)
                            hasSavedChatCredential = true
                            chatSettingsStatus = "已加密保存"
                        } else {
                            chatSettingsStatus = "保存失败"
                        }
                    }
                },
                onDeleteCredential = {
                    chatGenerationPreferences.credentialHandle?.let { handle ->
                        runCatching { chatCredentialStore.delete(handle) }
                    }
                    chatGenerationPreferences.setCredentialHandle(null)
                    apiKey = ""
                    hasSavedChatCredential = false
                    chatSettingsStatus = "已删除已保存密钥"
                },
                onStreamChange = { enabled ->
                    streamEnabled = enabled
                    chatGenerationPreferences.setStreamEnabled(enabled)
                },
                onThinkingChange = { enabled ->
                    thinkingEnabled = enabled
                    chatGenerationPreferences.setThinkingEnabled(enabled)
                },
            )

            ChatMemorySettingsPanel(
                settings = settings,
                baseUrl = memoryBaseUrl,
                modelId = memoryModelId,
                apiKey = memoryApiKey,
                hasSavedCredential = hasSavedMemoryCredential,
                credentialStore = memoryCredentialStore,
                diagnosticStore = diagnosticStore,
                selectedDimensions = selectedEmbeddingDimensions,
                dimensionMenuExpanded = dimensionMenuExpanded,
                onBaseUrlChange = { memoryBaseUrl = it },
                onModelIdChange = { memoryModelId = it },
                onApiKeyChange = { memoryApiKey = it },
                onSavedCredentialChange = { hasSavedMemoryCredential = it },
                onStatusChange = { status = it },
                onDimensionsMenuExpandedChange = { dimensionMenuExpanded = it },
                extractionEnabled = memoryExtractionEnabled,
                onExtractionEnabledChange = { enabled ->
                    memoryExtractionEnabled = enabled
                    settings.edit().putBoolean("memory_extraction_enabled", enabled).apply()
                    status = if (enabled) "已启用记忆整理。" else "已关闭记忆整理，仅保留检索。"
                },
                onDimensionsSelected = { dimensions ->
                    selectedEmbeddingDimensions = dimensions
                    dimensionMenuExpanded = false
                    status = ChatEmbeddingDimensionsSelectionCoordinator(
                        settings = settings,
                        diagnosticStore = diagnosticStore,
                    ).select(dimensions)
                },
            )
            }
            } else {
            ChatHistoryMessageList(
                history = history,
                busy = busy,
                listState = listState,
                onSelectVariant = { messageId, requested ->
                    val index = history.indexOfFirst { it.id == messageId }
                    if (index >= 0) {
                        val line = history[index]
                        val target = MessageVariants.select(line.variants, requested)
                        history[index] = line.copy(
                            content = line.variants.getOrElse(target) { line.content },
                            activeVariant = target,
                        )
                        persistHistory()
                        diagnosticStore.info("chat.swipe.selected", "variants=${line.variants.size}")
                    }
                },
                onRegenerate = ::regenerateReply,
            )
            if (usageSummary.isNotBlank()) {
                Text(
                    text = usageSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatMessageComposerInput(
                    draft = chatUiState.draft,
                    enabled = !busy && !historyLoadFailed,
                    onDraftChange = chatViewModel::updateDraft,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val preparedRequest = ChatSendRequestPreflightCoordinator(
                            memoryCredentialStore = memoryCredentialStore,
                            settings = settings,
                            setStatus = { status = it },
                        ).prepare(
                            userText = chatViewModel.uiState.value.draft,
                            busy = busy,
                            worldbooksReady = worldbookSession.ready,
                            baseUrl = baseUrl,
                            modelId = modelId,
                            apiKey = apiKey,
                            memoryBaseUrl = memoryBaseUrl,
                            memoryModelId = memoryModelId,
                            memoryApiKey = memoryApiKey,
                        ) ?: return@Button
                        val userText = preparedRequest.userText
                        val providersForRequest = preparedRequest.providers
                        val client = preparedRequest.client
                        val openingMessage = characterCards.firstOrNull { it.id == selectedCharacterId }
                            ?.card?.firstMessage?.trim().orEmpty()
                        val historyBeforeTurn = history.toList()
                        val currentSession = sessions.firstOrNull { it.id == memoryConversationId }
                        val groupMembers = currentSession?.participants
                            ?.map { GroupPromptAssembler.Member(it.id, it.name, "") }
                            .orEmpty()
                        val isGroupTurn = currentSession?.kind == SessionKind.GROUP &&
                            groupMembers.isNotEmpty()
                        val groupPrepared = if (isGroupTurn) {
                            val cardDefinition = characterCards
                                .firstOrNull { it.id == selectedCharacterId }
                                ?.card
                                ?.let(ChatCharacterPromptFormatter::format)
                                ?.takeIf(String::isNotBlank)
                                ?: "You are taking part in a multi-character roleplay."
                            GroupChatSendPlanner.prepare(
                                stableSystemPrefix = cardDefinition,
                                members = groupMembers,
                                history = historyBeforeTurn,
                                draft = userText,
                                lastSpeakerId = lastGroupSpeakerId,
                            )
                        } else {
                            null
                        }
                        ChatSendTurnStartCoordinator(
                            history = history,
                            persistHistory = ::persistHistory,
                            clearDraft = chatViewModel::clearDraft,
                            setBusy = { busy = it },
                            setMemoryStatusNotice = { memoryStatusNotice = it },
                            setStatus = { status = it },
                        ).start(openingMessage, userText)
                        val messages = ChatTurnPromptPreparationCoordinator(
                            settings = settings,
                            diagnosticStore = diagnosticStore,
                            mainHandler = mainHandler,
                            setStatus = { status = it },
                        ).prepare(
                            history = history,
                            userText = userText,
                            characterCards = characterCards,
                            selectedCharacterId = selectedCharacterId,
                            localWorldbookEntries = worldbookSession.entries,
                            worldbookState = worldbookSession.activation.activationState,
                            worldbookStateKey = worldbookSession.activation.stateKey,
                            groupContext = groupPrepared?.let { prepared ->
                                GroupChatPromptContext(
                                    members = groupMembers,
                                    speakerName = prepared.speakerName,
                                )
                            },
                        )
                        lastPromptMessages = messages
                        val baseObserver = ChatStreamObserverCoordinator(
                            mainHandler = mainHandler,
                            history = history,
                            diagnosticStore = diagnosticStore,
                            setBusy = { busy = it },
                            setStatus = { status = it },
                            memoryStatusNotice = { memoryStatusNotice },
                            setUsageSummary = { usageSummary = it },
                            persistHistory = ::persistHistory,
                            scheduleMemoryExtraction = { providers ->
                                if (memoryExtractionEnabled) {
                                    memoryOperationsCoordinator.scheduleExtractionIfDue(
                                        providers,
                                        history.toList(),
                                    )
                                }
                            },
                        ).create(providersForRequest)
                        val observer = if (groupPrepared != null) {
                            object : ChatStreamObserver {
                                override fun onDelta(text: String) = baseObserver.onDelta(text)

                                override fun onUsage(usage: ChatTokenUsage) = baseObserver.onUsage(usage)

                                override fun onFailure(failure: ChatStreamFailure) =
                                    baseObserver.onFailure(failure)

                                override fun onComplete() {
                                    mainHandler.post {
                                        val index = history.indexOfLast { it.role == "assistant" }
                                        if (index >= 0) {
                                            history[index] = history[index].copy(
                                                speakerId = groupPrepared.speakerId,
                                                speakerName = groupPrepared.speakerName,
                                            )
                                        }
                                        lastGroupSpeakerId = groupPrepared.speakerId
                                        persistHistory()
                                    }
                                    baseObserver.onComplete()
                                }
                            }
                        } else {
                            baseObserver
                        }
                        ChatSendStreamFallbackCoordinator(
                            coroutineScope = coroutineScope,
                            diagnosticStore = diagnosticStore,
                            history = history,
                            setBusy = { busy = it },
                            setStatus = { status = it },
                            persistHistory = ::persistHistory,
                        ).launch(
                            memoryStream = {
                                ChatMemoryRuntimeCoordinator(
                                    context = context,
                                    mainHandler = mainHandler,
                                    diagnosticStore = diagnosticStore,
                                    memoryScopeId = memoryScopeId,
                                    memoryConversationId = memoryConversationId,
                                    indexRebuildCoordinator = memoryOperationsCoordinator.createIndexRebuildCoordinator(),
                                    setStatus = { status = it },
                                    setMemoryStatusNotice = { memoryStatusNotice = it },
                                ).stream(client, messages, observer, maxTokensText)
                            },
                            ordinaryStream = {
                                client.stream(
                                    messages,
                                    observer,
                                    ChatGenerationOptions(
                                        maxTokens = maxTokensText.toIntOrNull()
                                            ?.takeIf { it in 1..200_000 } ?: 4096,
                                        stream = chatGenerationPreferences.streamEnabled,
                                        thinkingEnabled = chatGenerationPreferences.thinkingEnabled,
                                    ),
                                )
                            },
                        )
                    },
                    enabled = !busy && historyReady && !historyLoadFailed && worldbookSession.ready,
                ) {
                    Text("➤", style = MaterialTheme.typography.titleLarge)
                }
            }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000
