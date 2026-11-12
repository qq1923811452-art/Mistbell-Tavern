@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "MagicNumber", "TooGenericExceptionCaught")

package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.core.security.ChatCredentialStore
import com.mistbell.tavern.v3.core.storage.AtomicMemoryOverrideStore
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames
import com.mistbell.tavern.v3.core.storage.MemoryScopeId
import com.mistbell.tavern.v3.core.storage.MemoryScopeMigration
import com.mistbell.tavern.v3.feature.memory.MemoryEditDraft
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobRepository
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobStore
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionQueueWorker
import com.mistbell.tavern.v3.feature.memory.MemoryStatusReader
import com.mistbell.tavern.v3.feature.memory.MemoryStatusSnapshot
import com.mistbell.tavern.v3.feature.memory.SessionMemoryLoadResult
import com.mistbell.tavern.v3.feature.memory.SessionMemoryRepository
import com.mistbell.tavern.v3.feature.memory.SessionMemorySaveResult
import com.mistbell.tavern.v3.feature.sessions.SessionKind
import com.mistbell.tavern.v3.feature.sessions.SessionParticipant
import com.mistbell.tavern.v3.feature.sessions.SessionStore
import com.mistbell.tavern.v3.feature.sessions.ui.GroupCandidate
import com.mistbell.tavern.v3.feature.sessions.ui.SessionListScreen
import com.mistbell.tavern.v3.feature.worldbook.WorldBookLibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal val CHAT_EMBEDDING_DIMENSION_OPTIONS = listOf(128, 256, 512, 768, 1024, 1536, 2048)

/**
 * Minimal end-to-end chat screen for 3.0 development.
 * Provider settings and the API key remain in screen memory only; they are not persisted.
 */
@Suppress("ReturnCount") // Loading, memory and settings surfaces exit before composing the chat.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onSessionListVisibilityChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel()
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val settings =
        remember(context) {
            context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
        }
    var baseUrl by remember(settings) {
        mutableStateOf(settings.getString("chat_api_base_url", "https://api.odys.work/v1") ?: "https://api.odys.work/v1")
    }
    var modelId by remember(settings) {
        mutableStateOf(settings.getString("chat_model_id", "") ?: "")
    }
    val chatGenerationPreferences =
        remember(context) {
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
    val requestGeneration = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var memoryBaseUrl by remember(settings) {
        mutableStateOf(settings.getString("memory_api_base_url", "https://api.odys.work/v1") ?: "https://api.odys.work/v1")
    }
    var memoryModelId by remember(settings) {
        mutableStateOf(settings.getString("memory_model_id", "") ?: "")
    }
    var memoryApiKey by remember { mutableStateOf("") }
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
    val sessionStore = remember(context) { SessionStore(context) }
    // Resolve persisted kind before selecting a memory scope, including initial app startup.
    var sessions by remember(sessionStore) { mutableStateOf(sessionStore.list()) }
    val isGroupMemoryScope = sessions.firstOrNull { it.id == memoryConversationId }?.kind == SessionKind.GROUP
    // Only one-to-one conversations share character memory. A group's other participants must
    // not write facts into the host character's private conversations.
    val conversationMemoryScopeId =
        remember(activeMemoryRoleId, memoryConversationId) {
            MemoryScopeId.forRoleAndConversation(activeMemoryRoleId, memoryConversationId)
        }
    val memoryScopeId =
        remember(activeMemoryRoleId, conversationMemoryScopeId, isGroupMemoryScope) {
            if (isGroupMemoryScope) conversationMemoryScopeId else MemoryScopeId.forRole(activeMemoryRoleId)
        }
    var showSessionList by remember { mutableStateOf(true) }
    LaunchedEffect(showSessionList) { onSessionListVisibilityChange(showSessionList) }
    var lastGroupSpeakerId by remember { mutableStateOf<String?>(null) }
    var lastPromptMessages by remember { mutableStateOf<List<PromptMessage>>(emptyList()) }
    var memoryExtractionEnabled by remember {
        mutableStateOf(settings.getBoolean("memory_extraction_enabled", true))
    }
    var showPromptPreview by remember { mutableStateOf(false) }
    var showCharacterPicker by remember { mutableStateOf(false) }
    var showCreateSession by remember { mutableStateOf(false) }
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
    val historyDeletionGuard = remember { HistoryDeletionGuard() }
    var memoryStatusNotice by remember { mutableStateOf("") }
    var maxTokensText by remember(settings) {
        mutableStateOf(settings.getInt("chat_max_tokens", 4096).coerceIn(1, 200_000).toString())
    }
    var usageSummary by remember { mutableStateOf("Token 用量：本轮尚无服务端 usage 数据") }
    val history = remember { mutableStateListOf<ChatLine>() }
    var loadedHistoryScope by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val conversationHistoryScope = memoryConversationId.ifBlank { conversationMemoryScopeId }
    val historyWindowPlanner = remember(conversationHistoryScope) { StableConversationWindowPlanner() }

    // Manual memory edits live in their own scope-partitioned store; the ledger stays untouched.
    val sessionMemoryRepository =
        remember(context, memoryScopeId) {
            SessionMemoryRepository(
                factStore =
                    AtomicTemporalFactStore(
                        context.applicationContext,
                        MemoryScopeFileNames.forScope(AtomicTemporalFactStore.DEFAULT_FILE_NAME, memoryScopeId),
                    ),
                memoryTextStore =
                    AtomicMemoryTextStore(
                        context.applicationContext,
                        MemoryScopeFileNames.forScope(AtomicMemoryTextStore.DEFAULT_FILE_NAME, memoryScopeId),
                    ),
                overrideStore =
                    AtomicMemoryOverrideStore(
                        context.applicationContext,
                        MemoryScopeFileNames.forScope(AtomicMemoryOverrideStore.DEFAULT_FILE_NAME, memoryScopeId),
                    ),
            )
        }
    var memorySummary by remember(memoryScopeId) { mutableStateOf<String?>(null) }
    var showMemoryScreen by remember(memoryScopeId) { mutableStateOf(false) }
    var memoryScreenState by remember(memoryScopeId) {
        mutableStateOf<SessionMemoryUiState>(SessionMemoryUiState.Loading)
    }
    var memoryStatus by remember(memoryScopeId) { mutableStateOf<MemoryStatusSnapshot?>(null) }
    val diagnosticStore = remember(context) { DiagnosticLogStore(context.applicationContext.filesDir) }
    val historyStore =
        remember(context, conversationHistoryScope) {
            val legacyStore =
                ChatHistoryStore(
                    context.applicationContext,
                    MemoryScopeFileNames.forScope(ChatHistoryStore.DEFAULT_FILE_NAME, conversationHistoryScope),
                )
            SqliteChatHistoryRepository(
                context.applicationContext,
                conversationHistoryScope,
                legacyStore,
                legacyScopeId =
                    conversationMemoryScopeId
                        .takeIf { it.isNotBlank() && it != conversationHistoryScope },
                legacyFileStore =
                    conversationMemoryScopeId
                        .takeIf { it.isNotBlank() && it != conversationHistoryScope }
                        ?.let { scope ->
                            ChatHistoryStore(
                                context.applicationContext,
                                MemoryScopeFileNames.forScope(ChatHistoryStore.DEFAULT_FILE_NAME, scope),
                            )
                        },
                onDiagnostic = { event, detail -> diagnosticStore.warn(event, detail) },
            )
        }
    DisposableEffect(historyStore) {
        onDispose { historyStore.close() }
    }
    val memoryCredentialStore = remember(context) { AndroidKeystoreMemoryCredentialStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun loadSessionMemory(): SessionMemoryUiState =
        withContext(Dispatchers.IO) {
            when (val loaded = sessionMemoryRepository.load(memoryScopeId)) {
                SessionMemoryLoadResult.Missing -> SessionMemoryUiState.Ready(emptyList(), 0L)
                is SessionMemoryLoadResult.Unavailable -> SessionMemoryUiState.Failed(loaded.safeReason)
                is SessionMemoryLoadResult.Loaded ->
                    SessionMemoryUiState.Ready(
                        entries = loaded.entries,
                        revision = loaded.revision,
                        issue = loaded.overrideIssue,
                    )
            }
        }

    fun refreshSessionMemory(notice: String? = null) {
        memoryScreenState = SessionMemoryUiState.Loading
        coroutineScope.launch {
            memoryStatus =
                withContext(Dispatchers.IO) {
                    MemoryStatusReader.read(context.applicationContext, memoryScopeId)
                }
            val loaded = loadSessionMemory()
            memoryScreenState =
                if (loaded is SessionMemoryUiState.Ready) {
                    loaded.copy(notice = notice ?: loaded.notice)
                } else {
                    loaded
                }
        }
    }
    val historySaveMutex = remember { Mutex() }
    val historySaveRevision = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var memoryScopeReady by remember(memoryScopeId, conversationMemoryScopeId) { mutableStateOf(false) }
    var memoryScopeFailed by remember(memoryScopeId, conversationMemoryScopeId) { mutableStateOf(false) }
    var memoryScopeRetry by remember(memoryScopeId, conversationMemoryScopeId) { mutableStateOf(0) }
    // Queue claim validation and the copy are serialized: an old in-flight worker cannot write
    // into the legacy store between copying it and rebinding its persisted job.
    LaunchedEffect(memoryScopeId, conversationMemoryScopeId, memoryScopeRetry) {
        memoryScopeReady = false
        memoryScopeFailed = false
        if (isGroupMemoryScope) {
            memoryScopeReady = true
            return@LaunchedEffect
        }
        val migrated =
            withContext(Dispatchers.IO) {
                runCatching {
                    MemoryExtractionJobRepository(MemoryExtractionJobStore(context.applicationContext)).migrateScope(
                        legacyMemoryScopeId = conversationMemoryScopeId,
                        roleMemoryScopeId = memoryScopeId,
                        historyScopeId = conversationHistoryScope,
                    ) {
                        !MemoryScopeMigration.copyConversationScopeToRole(
                            context.applicationContext,
                            memoryScopeId,
                            conversationMemoryScopeId,
                        ).failed
                    }
                }.isSuccess
            }
        if (migrated) MemoryExtractionQueueWorker.enqueue(context.applicationContext)
        memoryScopeReady = migrated
        memoryScopeFailed = !migrated
        diagnosticStore.info(
            "memory.scope.migration_checked",
            "ready=$migrated; scope=${memoryScopeId.take(8)}",
        )
    }
    val activeWorldbookId =
        remember(settings) {
            settings.getString("active_worldbook_id", "") ?: ""
        }
    val worldbookLibrary =
        remember(context) {
            WorldBookLibraryStore(File(context.filesDir, "worldbooks")).list()
        }
    val sessionWorldbookId =
        sessions.firstOrNull { it.id == memoryConversationId }
            ?.worldbookId.orEmpty()
    val sessionWorldbookExists = worldbookLibrary.any { it.id == sessionWorldbookId }
    val effectiveWorldbookId =
        when {
            sessionWorldbookId.isNotBlank() && sessionWorldbookExists -> sessionWorldbookId
            else -> activeWorldbookId
        }
    val worldbookSession =
        rememberChatWorldBookSessionState(
            context = context,
            settings = settings,
            diagnosticStore = diagnosticStore,
            roleId = activeMemoryRoleId,
            conversationId = memoryConversationId,
            worldbookId = effectiveWorldbookId,
        )
    var characterCards by remember(context) { mutableStateOf(emptyList<com.mistbell.tavern.v3.feature.character.StoredCharacterCard>()) }
    val selectedCharacter = characterCards.firstOrNull { it.id == selectedCharacterId }

    val libraryLoadingCoordinator =
        remember(context, diagnosticStore) {
            ChatLibraryLoadingCoordinator(context.applicationContext, diagnosticStore)
        }

    ChatLibraryLoadingLifecycle(
        context = context,
        coordinator = libraryLoadingCoordinator,
        selectedCharacterId = selectedCharacterId,
        setSelectedCharacterId = { selectedCharacterId = it },
        setCharacterCards = { characterCards = it },
        revision = com.mistbell.tavern.v3.feature.character.CharacterLibraryRevision.value,
    )
    val memoryExtractionInFlight = remember { mutableSetOf<String>() }

    val historySaveCoordinator =
        remember(
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

    fun savePromptSnapshot(
        conversationId: String,
        messages: List<PromptMessage>,
    ) {
        if (conversationId.isBlank()) return
        val encoded =
            JSONArray().apply {
                messages.forEach { message ->
                    put(JSONObject().put("role", message.role.name).put("content", message.content))
                }
            }.toString()
        settings.edit().putString("chat_last_prompt_$conversationId", encoded).apply()
    }

    fun loadPromptSnapshot(conversationId: String): List<PromptMessage> {
        val raw =
            if (conversationId.isBlank()) {
                null
            } else {
                settings.getString("chat_last_prompt_$conversationId", null)
            }
        return raw?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val role =
                        runCatching { PromptRole.valueOf(item.optString("role")) }.getOrNull()
                            ?: return@mapNotNull null
                    PromptMessage(role, item.optString("content"))
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    fun clearPromptSnapshot(conversationId: String = memoryConversationId) {
        if (conversationId.isNotBlank()) {
            settings.edit().remove("chat_last_prompt_$conversationId").apply()
        }
        lastPromptMessages = emptyList()
    }

    fun saveUsageSummary(
        conversationId: String,
        summary: String,
    ) {
        if (conversationId.isNotBlank()) {
            settings.edit().putString("chat_usage_$conversationId", summary).apply()
        }
    }

    fun effectiveMaxTokens(): Int =
        settings.getInt("chat_max_tokens", maxTokensText.toIntOrNull() ?: 4096)
            .coerceIn(1, 200_000)

    fun persistHistory() {
        if (historyDeletionGuard.isDeleting(memoryConversationId)) {
            status = "正在删除会话，暂不保存历史。"
            return
        }
        val startedNanos = System.nanoTime()
        val snapshot = ChatHistorySnapshotFactory.create(history)
        historySaveCoordinator.save(snapshot)
        val preview =
            snapshot.lastOrNull { it.content.isNotBlank() }
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

    val memoryOperationsCoordinator =
        remember(
            context,
            memoryCredentialStore,
            historyStore,
            historySaveMutex,
            coroutineScope,
            diagnosticStore,
            memoryScopeId,
            conversationHistoryScope,
        ) {
            ChatMemoryOperationsCoordinator(
                context = context,
                memoryCredentialStore = memoryCredentialStore,
                historyStore = historyStore,
                historySaveMutex = historySaveMutex,
                coroutineScope = coroutineScope,
                diagnosticStore = diagnosticStore,
                memoryScopeId = memoryScopeId,
                historyScopeId = conversationHistoryScope,
                isIndexRebuildInFlight = { memoryIndexRebuildInFlight },
                setIndexRebuildInFlight = { memoryIndexRebuildInFlight = it },
                isChatBusy = { busy },
                setStatus = { status = it },
            )
        }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun regenerateReply(targetMessageId: String) {
        if (busy) {
            status = "生成中，请先等待当前回复完成。"
            return
        }
        val plan = RegeneratePlanner.plan(history.toList(), targetMessageId)
        if (plan == null) {
            status = "无法重新生成：找不到对应的用户消息。"
            return
        }
        val requestToken = requestGeneration.incrementAndGet()

        fun ownsRequest(): Boolean = requestGeneration.get() == requestToken
        val requestConversationId = memoryConversationId
        val requestMemoryScopeId = memoryScopeId
        val requestCharacterId = selectedCharacterId
        val requestWorldbookEntries = worldbookSession.entries
        val requestWorldbookState = worldbookSession.activation.activationState
        val requestWorldbookStateKey = worldbookSession.activation.stateKey
        busy = true
        status = "正在准备重新生成…"
        coroutineScope.launch {
            try {
                val configuration =
                    ChatRequestClientCoordinator(
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
                    )
                if (configuration == null) {
                    busy = false
                    status = "重新生成失败：API 配置不完整。"
                    return@launch
                }
                val messages =
                    withContext(Dispatchers.IO) {
                        ChatTurnPromptPreparationCoordinator(
                            settings = settings,
                            diagnosticStore = diagnosticStore,
                            mainHandler = mainHandler,
                            setStatus = { status = it },
                            historyPlanner = historyWindowPlanner,
                            historyWindowCache = historyStore.windowStateCache,
                        ).prepare(
                            historyScopeId = requestConversationId.ifBlank { requestMemoryScopeId },
                            history = plan.prefixHistory,
                            userText = plan.userText,
                            characterCards = characterCards,
                            selectedCharacterId = requestCharacterId,
                            localWorldbookEntries = requestWorldbookEntries,
                            worldbookState = requestWorldbookState,
                            worldbookStateKey = requestWorldbookStateKey,
                            historyIsComplete = true,
                            invalidateHistoryWindow = true,
                        )
                    }
                if (memoryConversationId != requestConversationId || !ownsRequest()) {
                    if (ownsRequest()) {
                        busy = false
                        status = "会话已切换，重新生成已取消。"
                    }
                    return@launch
                }
                lastPromptMessages = messages
                savePromptSnapshot(requestConversationId, messages)
                status = "正在重新生成…"
                val generated = StringBuilder()
                var regenerateFinishReason: ChatFinishReason? = null
                val observer =
                    object : ChatStreamObserver {
                        override fun onDelta(text: String) {
                            generated.append(text)
                        }

                        override fun onFinish(reason: ChatFinishReason) {
                            regenerateFinishReason = reason
                            diagnosticStore.info("chat.stream.finished", "reason=${reason.name}; regenerate=true")
                        }

                        override fun onUsage(usage: ChatTokenUsage) {
                            mainHandler.post {
                                if (!ownsRequest() || memoryConversationId != requestConversationId) return@post
                                val summary = formatUsageSummary(usage)
                                usageSummary = summary
                                saveUsageSummary(requestConversationId, summary)
                            }
                        }

                        override fun onComplete() {
                            mainHandler.post {
                                if (!ownsRequest() || memoryConversationId != requestConversationId) return@post
                                val index = history.indexOfFirst { it.id == targetMessageId }
                                val replacement =
                                    if (index >= 0) {
                                        val line = history[index]
                                        val base = line.variants.ifEmpty { listOf(line.content) }
                                        val variants = MessageVariants.append(base, generated.toString())
                                        if (variants !== base) {
                                            history[index] =
                                                line.copy(
                                                    content = variants.last(),
                                                    activeVariant = variants.lastIndex,
                                                    variants = variants,
                                                )
                                            persistHistory()
                                            diagnosticStore.info(
                                                "chat.regenerate.completed",
                                                "variants=${variants.size}",
                                            )
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                busy = false
                                status =
                                    when {
                                        !replacement -> "生成完成，但目标消息已不存在。"
                                        regenerateFinishReason == ChatFinishReason.LENGTH ->
                                            "已生成新版本，但达到输出上限。"
                                        regenerateFinishReason == ChatFinishReason.EOF ->
                                            "已生成新版本，但响应提前结束。"
                                        else -> "已生成新版本。"
                                    }
                            }
                        }

                        override fun onFailure(failure: ChatStreamFailure) {
                            mainHandler.post {
                                if (!ownsRequest() || memoryConversationId != requestConversationId) return@post
                                busy = false
                                status = "重新生成失败：${failure.safeMessage}"
                            }
                        }
                    }
                ChatMemoryRuntimeCoordinator(
                    context = context,
                    mainHandler = mainHandler,
                    diagnosticStore = diagnosticStore,
                    memoryScopeId = requestMemoryScopeId,
                    memoryConversationId = requestConversationId,
                    indexRebuildCoordinator = memoryOperationsCoordinator.createIndexRebuildCoordinator(),
                    setStatus = { status = it },
                    setMemoryStatusNotice = { memoryStatusNotice = it },
                ).stream(
                    configuration.client,
                    messages,
                    observer,
                    effectiveMaxTokens(),
                    memoryQuery = plan.userText,
                )
            } catch (error: Exception) {
                if (ownsRequest()) {
                    busy = false
                    status = "重新生成准备失败：${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    val sessionDocumentActions =
        rememberChatSessionDocumentActions(
            context = context,
            coroutineScope = coroutineScope,
            historyStore = historyStore,
            historySaveMutex = historySaveMutex,
            historySaveRevision = historySaveRevision,
            history = history,
            conversationId = memoryConversationId,
            setStatus = { status = it },
            sessionTitle =
                sessions.firstOrNull { it.id == memoryConversationId }?.title
                    ?: "Mistbell Tavern session",
            sessionKind =
                sessions.firstOrNull { it.id == memoryConversationId }?.kind
                    ?: SessionKind.SINGLE,
            sessionParticipants =
                sessions.firstOrNull { it.id == memoryConversationId }?.participants
                    .orEmpty(),
            onImported = { archive ->
                val metadataUpdated =
                    sessionStore.updateImportedMetadata(
                        id = memoryConversationId,
                        title = archive.title,
                        kind = archive.kind,
                        participants = archive.participants,
                    )
                if (!metadataUpdated) {
                    status = "会话已导入，但列表标题/类型未能同步，请重新打开应用检查。"
                }
                val preview =
                    history.lastOrNull { it.content.isNotBlank() }
                        ?.content
                        ?.trim()
                        ?.replace('\n', ' ')
                        ?.take(80)
                        .orEmpty()
                if (memoryConversationId.isNotBlank()) {
                    if (sessionStore.touch(memoryConversationId, preview, history.size)) {
                        sessions = sessionStore.list()
                    }
                }
            },
        )

    val historyLoadCoordinator =
        remember(historyStore, diagnosticStore) {
            ChatHistoryLoadCoordinator(historyStore, diagnosticStore)
        }

    ChatHistoryLoadLifecycle(
        context = context,
        historyStore = historyStore,
        historyLoadCoordinator = historyLoadCoordinator,
        diagnosticStore = diagnosticStore,
        history = history,
        setHistoryReady = {
            historyReady = it
            loadedHistoryScope = conversationHistoryScope.takeIf { _ -> it }
        },
        setHistoryLoadFailed = { historyLoadFailed = it },
        setStatus = { status = it },
    )

    LaunchedEffect(historyReady, historyLoadFailed, selectedCharacterId, memoryConversationId) {
        if (!historyReady || historyLoadFailed || history.isNotEmpty()) return@LaunchedEffect
        val session = sessions.firstOrNull { it.id == memoryConversationId }
        if (session != null && session.kind != SessionKind.SINGLE) return@LaunchedEffect
        val card =
            characterCards.firstOrNull { it.id == selectedCharacterId }?.card
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

    LaunchedEffect(memoryConversationId) {
        lastPromptMessages = loadPromptSnapshot(memoryConversationId)
        usageSummary = settings.getString("chat_usage_$memoryConversationId", null)
            ?: "Token 用量：本轮尚无服务端 usage 数据"
    }

    // Do not compose interactive memory/chat surfaces before migration and queue rebinding.
    // Loading the repository itself may materialize empty projections and block a later copy.
    if (!memoryScopeReady) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (memoryScopeFailed) "记忆迁移未完成，原始数据已保留。请重试后再继续。" else "正在准备角色共享记忆…")
            if (memoryScopeFailed) {
                Button(onClick = { memoryScopeRetry++ }) { Text("重试") }
            }
            Button(onClick = onBack) { Text("返回") }
        }
        return
    }

    if (showCreateSession) {
        CreateSessionDialog(
            characters = characterCards,
            worldbooks = worldbookLibrary.map { it.id to it.name },
            initialRoleId = selectedCharacterId,
            onDismiss = { showCreateSession = false },
            onConfirm = { roleId, worldbookId ->
                val created =
                    runCatching {
                        sessionStore.create(
                            roleId = roleId.ifBlank { activeMemoryRoleId },
                            title = "新对话",
                            worldbookId = worldbookId,
                        )
                    }.getOrNull()
                if (created != null) {
                    selectedCharacterId = roleId.ifBlank { activeMemoryRoleId }
                    sessionStore.setRoleId(created.id, selectedCharacterId)
                    memoryConversationId = created.id
                    settings.edit().putString("memory_extraction_conversation_id", created.id).apply()
                    sessions = sessionStore.list()
                    lastPromptMessages = emptyList()
                    lastGroupSpeakerId = null
                    chatViewModel.clearDraft()
                    usageSummary = "Token 用量：本轮尚无服务端 usage 数据"
                    diagnosticStore.info("chat.session.created", "sessions=${sessions.size}")
                }
                showCreateSession = false
                showSessionList = false
            },
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
                lastGroupSpeakerId = null
                chatViewModel.clearDraft()
                lastPromptMessages = loadPromptSnapshot(id)
                usageSummary = settings.getString("chat_usage_$id", null)
                    ?: "Token 用量：本轮尚无服务端 usage 数据"
                diagnosticStore.info("chat.session.switched", "reason=click")
                showSessionList = false
            },
            onCreateSession = { showCreateSession = true },
            onRenameSession = { id, title ->
                if (sessionStore.rename(id, title)) {
                    sessions = sessionStore.list()
                    diagnosticStore.info("chat.session.renamed", "titleChars=${title.length}")
                }
            },
            onDeleteSession = { id ->
                historyDeletionGuard.begin(id)
                coroutineScope.launch {
                    try {
                        val isCurrent = id == conversationHistoryScope
                        if (isCurrent) historySaveRevision.incrementAndGet()
                        val historyDeleted =
                            runCatching {
                                if (isCurrent) {
                                    historySaveMutex.withLock {
                                        withContext(Dispatchers.IO) { historyStore.deleteAll() }
                                    }
                                } else {
                                    val legacy =
                                        ChatHistoryStore(
                                            context.applicationContext,
                                            MemoryScopeFileNames.forScope(ChatHistoryStore.DEFAULT_FILE_NAME, id),
                                        )
                                    SqliteChatHistoryRepository(context.applicationContext, id, legacy)
                                        .use { repository -> repository.deleteAll() }
                                }
                            }.onFailure { error ->
                                diagnosticStore.warn(
                                    "chat.session.history_delete_failed",
                                    error::class.java.simpleName,
                                )
                            }.isSuccess
                        if (!historyDeleted) {
                            status = "删除会话失败：聊天记录未能清除，请重试。"
                            return@launch
                        }
                        if (isCurrent && memoryConversationId == id) history.clear()
                        sessionStore.delete(id)
                        sessions = sessionStore.list()
                        diagnosticStore.info("chat.session.deleted", "remaining=${sessions.size}")
                        if (id == memoryConversationId) {
                            val next =
                                sessions.firstOrNull()
                                    ?: runCatching { sessionStore.create(activeMemoryRoleId, "新对话") }
                                        .getOrNull()
                                        ?.also {
                                            sessions = sessionStore.list()
                                        }
                            if (next != null) {
                                memoryConversationId = next.id
                                settings.edit().putString("memory_extraction_conversation_id", next.id).apply()
                                diagnosticStore.info("chat.session.switched", "reason=delete_fallback")
                            }
                        }
                    } finally {
                        historyDeletionGuard.end(id)
                    }
                }
            },
            onTogglePin = { id, pinned ->
                if (sessionStore.setPinned(id, pinned)) {
                    sessions = sessionStore.list()
                    diagnosticStore.info("chat.session.pinned", "pinned=$pinned")
                }
            },
            characters =
                characterCards.map { card ->
                    GroupCandidate(card.id, card.card.name, card.card.avatarFileName)
                },
            onCreateGroup = { ids ->
                val participants =
                    ids.mapNotNull { id ->
                        characterCards.firstOrNull { it.id == id }?.let { card ->
                            SessionParticipant(card.id, card.card.name)
                        }
                    }
                if (participants.size >= 2) {
                    val created =
                        runCatching {
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

    LaunchedEffect(showSessionSettings, memoryScopeId) {
        if (showSessionSettings) {
            memorySummary =
                withContext(Dispatchers.IO) {
                    when (val loaded = sessionMemoryRepository.load(memoryScopeId)) {
                        SessionMemoryLoadResult.Missing -> "暂无已保存记忆"
                        is SessionMemoryLoadResult.Unavailable -> "记忆暂时无法读取"
                        is SessionMemoryLoadResult.Loaded -> {
                            val current = loaded.entries.count { it.isCurrent }
                            "有效 $current 条 · 历史 ${loaded.entries.size - current} 条"
                        }
                    }
                }
        }
    }

    if (showMemoryScreen) {
        BackHandler { showMemoryScreen = false }
        SessionMemoryScreen(
            scopeTitle =
                if (isGroupMemoryScope) {
                    "当前群聊 · 独立记忆（作用域 ${memoryScopeId.take(8)}）"
                } else {
                    "当前角色 · 跨会话共享（作用域 ${memoryScopeId.take(8)}）"
                },
            state = memoryScreenState,
            status = memoryStatus,
            actions =
                SessionMemoryActions(
                    onBack = { showMemoryScreen = false },
                    onReload = { refreshSessionMemory() },
                    onSave = { factId, revision, subject, predicate, value ->
                        coroutineScope.launch {
                            val result =
                                withContext(Dispatchers.IO) {
                                    sessionMemoryRepository.saveEdit(
                                        memoryScopeId,
                                        factId,
                                        revision,
                                        MemoryEditDraft(subject, predicate, value),
                                    )
                                }
                            refreshSessionMemory(
                                when (result) {
                                    is SessionMemorySaveResult.Saved ->
                                        result.projectionWarning ?: "已保存；索引刷新后检索会使用新内容。"
                                    is SessionMemorySaveResult.Conflict ->
                                        "这条记忆已被其他修改更新，已刷新为最新版本，请重新编辑。"
                                    is SessionMemorySaveResult.Invalid -> result.safeReason
                                    SessionMemorySaveResult.NotFound -> "这条记忆已不存在，已刷新。"
                                    is SessionMemorySaveResult.Failed -> "保存失败：${result.safeReason}"
                                },
                            )
                        }
                    },
                    onRevert = { factId, revision ->
                        coroutineScope.launch {
                            val result =
                                withContext(Dispatchers.IO) {
                                    sessionMemoryRepository.revert(factId, revision)
                                }
                            refreshSessionMemory(
                                when (result) {
                                    is SessionMemorySaveResult.Saved -> result.projectionWarning ?: "已恢复自动抽取版本。"
                                    is SessionMemorySaveResult.Conflict -> "这条记忆已被其他修改更新，已刷新，请重试。"
                                    is SessionMemorySaveResult.Invalid -> result.safeReason
                                    SessionMemorySaveResult.NotFound -> "这条记忆已不存在，已刷新。"
                                    is SessionMemorySaveResult.Failed -> "恢复失败：${result.safeReason}"
                                },
                            )
                        }
                    },
                    onRetryFailed = {
                        coroutineScope.launch {
                            val retried =
                                withContext(Dispatchers.IO) {
                                    val handle =
                                        settings.getString("memory_api_credential_handle", null)
                                            ?.takeIf { it.isNotBlank() && memoryCredentialStore.contains(it) }
                                    if (handle == null) {
                                        null
                                    } else {
                                        MemoryExtractionJobRepository(
                                            MemoryExtractionJobStore(context.applicationContext),
                                        ).retryPermanentFailures(memoryScopeId, System.currentTimeMillis(), handle)
                                    }
                                }
                            if (retried == null) {
                                refreshSessionMemory("请先在记忆 API 设置中保存有效的 API Key，再重试失败任务。")
                                return@launch
                            }
                            if (retried > 0) {
                                MemoryExtractionQueueWorker.enqueue(context.applicationContext)
                                refreshSessionMemory("已重新排队 $retried 条失败任务；后台会重试。")
                            } else {
                                refreshSessionMemory("当前没有可重试的失败任务。")
                            }
                        }
                    },
                ),
        )
        return
    }

    if (showSessionSettings) {
        ChatSessionSettingsSheet(
            characters = characterCards,
            selectedCharacterId = selectedCharacterId,
            messageCount = history.size,
            memorySummary = memorySummary,
            onOpenMemory = {
                showSessionSettings = false
                memoryScreenState = SessionMemoryUiState.Loading
                showMemoryScreen = true
                refreshSessionMemory()
            },
            worldbooks = worldbookLibrary.map { book -> book.id to book.name },
            selectedWorldbookId = sessionWorldbookId,
            onSelectWorldbook = { worldbookId ->
                if (memoryConversationId.isNotBlank()) {
                    if (sessionStore.setWorldbookId(memoryConversationId, worldbookId)) {
                        sessions = sessionStore.list()
                        clearPromptSnapshot()
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
                clearPromptSnapshot()
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

    if (showCharacterPicker) {
        CharacterPickerSheet(
            characters = characterCards,
            selectedCharacterId = selectedCharacterId,
            onSelect = { characterId ->
                selectedCharacterId = characterId
                ChatCharacterSelectionCoordinator(settings).select(characterId)
                if (memoryConversationId.isNotBlank() && sessionStore.setRoleId(memoryConversationId, characterId)) {
                    sessions = sessionStore.list()
                }
                clearPromptSnapshot()
                showCharacterPicker = false
            },
            onDismiss = { showCharacterPicker = false },
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
            ChatTopBar(
                characterName = selectedCharacter?.card?.name ?: "聊天",
                avatarFileName = selectedCharacter?.card?.avatarFileName,
                showPrompt = lastPromptMessages.isNotEmpty(),
                onOpenSessions = {
                    if (busy) {
                        status = "生成中，请先停止再切换会话。"
                    } else {
                        showSessionList = true
                    }
                },
                onOpenCharacterPicker = { showCharacterPicker = true },
                onOpenPrompt = { showPromptPreview = true },
                onOpenSettings = { showSessionSettings = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chatConversationPane(
                pane =
                    ConversationPaneState(
                        conversationId = conversationHistoryScope,
                        historyReady =
                            historyReady && !historyLoadFailed &&
                                loadedHistoryScope == conversationHistoryScope,
                        history = history,
                        busy = busy,
                    ),
                assistantAvatarFileName = selectedCharacter?.card?.avatarFileName,
                onSelectVariant = { messageId, requested ->
                    val index = history.indexOfFirst { it.id == messageId }
                    if (index >= 0) {
                        val line = history[index]
                        val target = MessageVariants.select(line.variants, requested)
                        history[index] =
                            line.copy(
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
            Box(modifier = Modifier.fillMaxWidth()) {
                ChatMessageComposerInput(
                    draft = chatUiState.draft,
                    enabled = !busy && !historyLoadFailed,
                    onDraftChange = chatViewModel::updateDraft,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 56.dp),
                )
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val requestToken = requestGeneration.incrementAndGet()

                            fun ownsSendRequest(): Boolean = requestGeneration.get() == requestToken
                            val requestConversationId = memoryConversationId
                            val requestMemoryScopeId = memoryScopeId
                            val requestCharacterId = selectedCharacterId
                            val requestWorldbookEntries = worldbookSession.entries
                            val requestWorldbookState = worldbookSession.activation.activationState
                            val requestWorldbookStateKey = worldbookSession.activation.stateKey
                            val preparedRequest =
                                ChatSendRequestPreflightCoordinator(
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
                                ) ?: return@launch
                            val userText = preparedRequest.userText
                            val providersForRequest = preparedRequest.providers
                            val client = preparedRequest.client
                            val openingMessage =
                                characterCards.firstOrNull { it.id == selectedCharacterId }
                                    ?.card?.firstMessage?.trim().orEmpty()
                            val historyBeforeTurn = history.toList()
                            val currentSession = sessions.firstOrNull { it.id == memoryConversationId }
                            val groupMembers =
                                currentSession?.participants
                                    ?.map { GroupPromptAssembler.Member(it.id, it.name, "") }
                                    .orEmpty()
                            val isGroupTurn =
                                currentSession?.kind == SessionKind.GROUP &&
                                    groupMembers.isNotEmpty()
                            val groupPrepared =
                                if (isGroupTurn) {
                                    val cardDefinition =
                                        characterCards
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
                            diagnosticStore.info("chat.send.started", "conversation=${requestConversationId.take(8)}")
                            val requestMaxTokens = effectiveMaxTokens()
                            diagnosticStore.info(
                                "chat.request.options",
                                "maxTokens=$requestMaxTokens; " +
                                    "stream=${chatGenerationPreferences.streamEnabled}; " +
                                    "thinking=${chatGenerationPreferences.thinkingEnabled}; " +
                                    "model=${ChatRequestDiagnostics.modelLabel(modelId)}; " +
                                    "host=${ChatRequestDiagnostics.endpointHost(baseUrl)}",
                            )
                            val assistantMessageId = history.lastOrNull { it.role == "assistant" }?.id.orEmpty()
                            val requestHistory = history.toList()
                            diagnosticStore.info("chat.send.prepare.begin", "history=${requestHistory.size}")
                            val messages =
                                try {
                                    withTimeoutOrNull(PROMPT_PREPARE_TIMEOUT_MS) {
                                        withContext(Dispatchers.IO) {
                                            ChatTurnPromptPreparationCoordinator(
                                                settings = settings,
                                                diagnosticStore = diagnosticStore,
                                                mainHandler = mainHandler,
                                                setStatus = { status = it },
                                                historyPlanner = historyWindowPlanner,
                                                historyWindowCache = historyStore.windowStateCache,
                                            ).prepare(
                                                historyScopeId = requestConversationId.ifBlank { requestMemoryScopeId },
                                                history = requestHistory,
                                                userText = userText,
                                                characterCards = characterCards,
                                                selectedCharacterId = requestCharacterId,
                                                localWorldbookEntries = requestWorldbookEntries,
                                                worldbookState = requestWorldbookState,
                                                worldbookStateKey = requestWorldbookStateKey,
                                                groupContext =
                                                    groupPrepared?.let { prepared ->
                                                        GroupChatPromptContext(
                                                            members = groupMembers,
                                                            speakerName = prepared.speakerName,
                                                        )
                                                    },
                                            )
                                        }
                                    }
                                } catch (error: Exception) {
                                    if (ownsSendRequest()) {
                                        busy = false
                                        status = "提示词准备失败：${error.message ?: error.javaClass.simpleName}"
                                    }
                                    return@launch
                                }
                            if (messages == null) {
                                if (ownsSendRequest()) {
                                    busy = false
                                    status = "提示词准备超时，请重试。"
                                }
                                return@launch
                            }
                            diagnosticStore.info("chat.send.prepare.done", "messages=${messages.size}")
                            if (!ownsSendRequest() || memoryConversationId != requestConversationId) {
                                if (ownsSendRequest()) {
                                    busy = false
                                    status = "会话已切换，本次发送已取消。"
                                }
                                return@launch
                            }
                            lastPromptMessages = messages
                            savePromptSnapshot(requestConversationId, messages)
                            val baseObserver =
                                ChatStreamObserverCoordinator(
                                    mainHandler = mainHandler,
                                    history = history,
                                    diagnosticStore = diagnosticStore,
                                    setBusy = { busy = it },
                                    setStatus = { status = it },
                                    memoryStatusNotice = { memoryStatusNotice },
                                    setUsageSummary = {
                                        usageSummary = it
                                        saveUsageSummary(requestConversationId, it)
                                    },
                                    persistHistory = ::persistHistory,
                                    scheduleMemoryExtraction = { providers ->
                                        if (memoryExtractionEnabled) {
                                            memoryOperationsCoordinator.scheduleExtractionIfDue(
                                                providers,
                                                history.toList(),
                                            )
                                        }
                                    },
                                ).create(
                                    providersForRequest = providersForRequest,
                                    conversationId = requestConversationId,
                                    assistantMessageId = assistantMessageId,
                                    isCurrentRequest = {
                                        requestGeneration.get() == requestToken &&
                                            memoryConversationId == requestConversationId
                                    },
                                )
                            val observer =
                                if (groupPrepared != null) {
                                    object : ChatStreamObserver {
                                        override fun onDelta(text: String) = baseObserver.onDelta(text)

                                        override fun onUsage(usage: ChatTokenUsage) = baseObserver.onUsage(usage)

                                        override fun onFinish(reason: ChatFinishReason) = baseObserver.onFinish(reason)

                                        override fun onFailure(failure: ChatStreamFailure) {
                                            baseObserver.onFailure(failure)
                                        }

                                        override fun onComplete() {
                                            mainHandler.post {
                                                if (!ownsSendRequest() ||
                                                    memoryConversationId != requestConversationId
                                                ) {
                                                    return@post
                                                }
                                                val index = history.indexOfFirst { it.id == assistantMessageId }
                                                if (index >= 0) {
                                                    history[index] =
                                                        history[index].copy(
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
                                isCurrentRequest = {
                                    ownsSendRequest() &&
                                        memoryConversationId == requestConversationId
                                },
                            ).launch(
                                memoryStream = {
                                    ChatMemoryRuntimeCoordinator(
                                        context = context,
                                        mainHandler = mainHandler,
                                        diagnosticStore = diagnosticStore,
                                        memoryScopeId = requestMemoryScopeId,
                                        memoryConversationId = requestConversationId,
                                        indexRebuildCoordinator = memoryOperationsCoordinator.createIndexRebuildCoordinator(),
                                        setStatus = { status = it },
                                        setMemoryStatusNotice = { memoryStatusNotice = it },
                                    ).stream(
                                        client,
                                        messages,
                                        observer,
                                        requestMaxTokens,
                                        memoryQuery = userText,
                                    )
                                },
                                ordinaryStream = {
                                    client.stream(
                                        messages,
                                        observer,
                                        ChatGenerationOptions(
                                            maxTokens = requestMaxTokens,
                                            stream = chatGenerationPreferences.streamEnabled,
                                            thinkingEnabled = chatGenerationPreferences.thinkingEnabled,
                                        ),
                                    )
                                },
                            )
                        }
                    },
                    enabled =
                        !busy && historyReady && !historyLoadFailed &&
                            worldbookSession.ready && chatUiState.draft.isNotBlank(),
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(48.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("➤", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000
private const val PROMPT_PREPARE_TIMEOUT_MS = 30_000L
