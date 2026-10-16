package com.mistbell.tavern.v3.feature.chat

import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuilder
import com.mistbell.tavern.v3.core.memory.MemoryIndexRebuildResult
import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextService
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.AtomicVectorSnapshotStore
import com.mistbell.tavern.v3.core.storage.PersistedVectorSnapshotProvider
import com.mistbell.tavern.v3.core.storage.VectorSnapshotLoadResult
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2MemoryRuntime
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2ModelAssetManager
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2RuntimeAdapter
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy
import com.mistbell.tavern.v3.feature.embedding.LiteRtLmEmbeddingGemma2Engine
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
import com.mistbell.tavern.v3.feature.memory.MemoryExtractionInput
import com.mistbell.tavern.v3.feature.memory.TemporalFactTextSynchronizer
import com.mistbell.tavern.v3.feature.memory.TemporalFactTextSyncResult
import com.mistbell.tavern.v3.feature.memory.TemporalFactExtractionPipeline
import com.mistbell.tavern.v3.feature.memory.TemporalFactPipelineOutcome

private val CHAT_EMBEDDING_DIMENSION_OPTIONS = listOf(128, 256, 512, 768, 1024, 1536, 2048)

private data class ChatLine(
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
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var historyReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("填写模型 ID 和 API 配置后即可测试聊天。") }
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
    val historyStore = remember(context) { ChatHistoryStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    val diagnosticStore = remember(context) { DiagnosticLogStore(context.applicationContext.filesDir) }
    val memoryExtractionInFlight = remember { mutableSetOf<String>() }

    fun persistHistory() {
        coroutineScope.launch {
            runCatching {
                val snapshot = history.map { line ->
                    PersistedChatMessage(
                        id = line.id,
                        role = line.role,
                        content = line.content,
                        createdAtEpochMillis = line.createdAtEpochMillis,
                        deliveryState = line.deliveryState,
                    )
                }
                withContext(Dispatchers.IO) { historyStore.save(snapshot) }
            }.onFailure { error ->
                diagnosticStore.warn(
                    "chat.history.save_failed",
                    "reason=${error::class.java.simpleName}",
                )
            }
        }
    }

    LaunchedEffect(historyStore) {
        val loaded = withContext(Dispatchers.IO) { historyStore.load() }
        when (loaded) {
            ChatHistoryLoadResult.Missing -> Unit
            is ChatHistoryLoadResult.Loaded -> {
                history.clear()
                history.addAll(loaded.messages.map { message ->
                    ChatLine(
                        id = message.id,
                        role = message.role,
                        content = message.content,
                        createdAtEpochMillis = message.createdAtEpochMillis,
                        deliveryState = message.deliveryState,
                    )
                })
                if (history.any { it.deliveryState == PersistedChatMessage.DeliveryState.PENDING }) {
                    status = "已恢复聊天记录；上次未完成的回复已保留为待处理状态。"
                }
            }
            ChatHistoryLoadResult.Corrupt -> {
                diagnosticStore.warn("chat.history.load_failed", "reason=corrupt_or_unsupported_format")
                status = "聊天记录文件损坏或版本不兼容；为避免覆盖原文件，未自动清空记录。"
            }
        }
        historyReady = true
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use(diagnosticStore::exportTo)
                            ?: throw IllegalStateException("无法创建日志文件。")
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

    fun scheduleMemoryExtractionIfDue(
        providers: ProviderProfileSet,
        memorySecret: String,
    ) {
        if (providers.profileFor(ProviderKind.MEMORY_EXTRACTION) == null) return
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
        val preferences = context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
        val lastSuccessfulTurnId = preferences.getString("memory_extraction_last_success_turn_id", null)
        val previousIndex = pairs.indexOfLast { it.first.id == lastSuccessfulTurnId }
        val pending = if (previousIndex >= 0) pairs.drop(previousIndex + 1) else pairs
        if (pending.size < 3) return
        val batch = pending.take(20)
        val batchEndId = batch.last().first.id
        if (!memoryExtractionInFlight.add(batchEndId)) return
        val conversationId = preferences.getString("memory_extraction_conversation_id", null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("memory_extraction_conversation_id", it).apply()
            }
        val turns = batch.mapIndexed { ordinal, pair ->
            EligibleConversationTurn(id = pair.first.id, ordinal = (ordinal + 1).toLong())
        }
        val textByTurnId = batch.associate { (user, assistant) ->
            user.id to ("User: " + user.content + "\nAssistant: " + assistant.content)
                .take(MemoryExtractionInput.MAX_TURN_TEXT_CHARS)
        }
        val input = runCatching {
            MemoryExtractionInput(
                conversationId = conversationId,
                turns = turns,
                textByTurnId = textByTurnId,
            )
        }.getOrElse { error ->
            memoryExtractionInFlight.remove(batchEndId)
            diagnosticStore.warn("memory.extraction.input_rejected", "reason=${error::class.java.simpleName}")
            status = "记忆整理未启动：本批对话超出输入限制。"
            return
        }
        val pipeline = TemporalFactExtractionPipeline(
            client = MemoryExtractionClient(
                providers = providers,
                credentials = MemoryCredentialResolver { handle ->
                    if (handle == "session-memory-key") memorySecret else null
                },
            ),
            factStore = AtomicTemporalFactStore(context.applicationContext),
            memoryTextStore = AtomicMemoryTextStore(context.applicationContext),
        )
        try {
            pipeline.extractAndPersist(input) { outcome ->
                mainHandler.post {
                    memoryExtractionInFlight.remove(batchEndId)
                    when (outcome) {
                        is TemporalFactPipelineOutcome.Failed -> {
                            diagnosticStore.warn("memory.extraction.failed", "reason=safe_failure")
                            status = "记忆整理失败：${outcome.safeMessage}；聊天回复已保留。"
                        }
                        is TemporalFactPipelineOutcome.Saved -> {
                            preferences.edit().putString("memory_extraction_last_success_turn_id", batchEndId).apply()
                            diagnosticStore.info(
                                "memory.extraction.saved",
                                "extracted=${outcome.extractedFacts}; current=${outcome.currentFacts}; indexed_texts=${outcome.indexedTextRecords}",
                            )
                            status = "记忆整理完成：本批提取 ${outcome.extractedFacts} 条事实，当前有效 ${outcome.currentFacts} 条。" +
                                (outcome.indexingWarning?.let { " $it" } ?: "")
                            // Drain additional complete batches immediately; otherwise >20 pending turns can stall until another chat reply.
                            scheduleMemoryExtractionIfDue(providers, memorySecret)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            memoryExtractionInFlight.remove(batchEndId)
            diagnosticStore.warn("memory.extraction.start_failed", "reason=${error::class.java.simpleName}")
            status = "记忆整理 API 未启动；聊天回复已保留。"
        }
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
                        "Base URL 和模型 ID 会保存在本机设置中；API Key 不会持久保存，离开页面后需要重新输入。",
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
                    Text(
                        "与聊天 API 分开配置。Base URL 和模型 ID 会保存在本机；密钥仅在当前页面内存中。每累计 3 轮已完成对话后异步整理，失败不会撤销聊天回复。",
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
                                        else -> ""
                                    }
                                },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("输入消息") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                minLines = 2,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val userText = draft.trim()
                        if (userText.isEmpty() || busy) return@Button
                        if (modelId.isBlank()) {
                            status = "请先填写模型 ID。"
                            return@Button
                        }
                        val profile = ProviderProfile(
                            id = "session-chat",
                            kind = ProviderKind.CHAT,
                            baseUrl = baseUrl.trim(),
                            modelId = modelId.trim(),
                            credentialHandle = if (apiKey.isBlank()) null else "session-chat-key",
                        )
                        val secretForThisRequest = apiKey
                        val memorySecretForThisRequest = memoryApiKey
                        val memoryProfile = memoryModelId.trim().takeIf(String::isNotBlank)?.let { configuredMemoryModel ->
                            ProviderProfile(
                                id = "session-memory-extraction",
                                kind = ProviderKind.MEMORY_EXTRACTION,
                                baseUrl = memoryBaseUrl.trim(),
                                modelId = configuredMemoryModel,
                                credentialHandle = if (memoryApiKey.isBlank()) null else "session-memory-key",
                            )
                        }
                        val providersForRequest = try {
                            ProviderProfileSet(
                                profiles = listOfNotNull(profile, memoryProfile),
                                selectedProfileIds = buildMap {
                                    put(ProviderKind.CHAT, profile.id)
                                    if (memoryProfile != null) put(ProviderKind.MEMORY_EXTRACTION, memoryProfile.id)
                                },
                            )
                        } catch (_: IllegalArgumentException) {
                            status = "聊天或记忆 API 配置无效，请检查 Base URL 和模型 ID。"
                            return@Button
                        }
                        val client = OpenAiCompatibleChatClient(
                            providers = providersForRequest,
                            credentials = CredentialResolver { handle ->
                                when (handle) {
                                    "session-chat-key" -> secretForThisRequest
                                    "session-memory-key" -> memorySecretForThisRequest
                                    else -> null
                                }
                            },
                        )
                        val now = System.currentTimeMillis()
                        history += ChatLine(
                            id = UUID.randomUUID().toString(),
                            role = "user",
                            content = userText,
                            createdAtEpochMillis = now,
                            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
                        )
                        history += ChatLine(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = "",
                            createdAtEpochMillis = now,
                            deliveryState = PersistedChatMessage.DeliveryState.PENDING,
                        )
                        persistHistory()
                        draft = ""
                        busy = true
                        status = "正在连接聊天 API……"
                        val messages = buildList {
                            add(PromptMessage(PromptRole.SYSTEM, "你是 Mistbell Tavern 的聊天助手。请直接、清晰地回答用户。"))
                            history.dropLast(2).forEach { line ->
                                if (line.content.isNotBlank()) {
                                    add(
                                        PromptMessage(
                                            if (line.role == "user") PromptRole.USER else PromptRole.ASSISTANT,
                                            line.content,
                                        ),
                                    )
                                }
                            }
                            add(PromptMessage(PromptRole.USER, userText))
                        }
                        var memoryEngine: LiteRtLmEmbeddingGemma2Engine? = null
                        val observer = object : ChatStreamObserver {
                            override fun onDelta(text: String) {
                                mainHandler.post {
                                    val last = history.lastOrNull()
                                    if (last?.role == "assistant") {
                                        history[history.lastIndex] = last.copy(content = last.content + text)
                                    }
                                }
                            }

                            override fun onComplete() {
                                memoryEngine?.close()
                                memoryEngine = null
                                mainHandler.post {
                                    busy = false
                                    status = "回复完成。"
                                    val last = history.lastOrNull()
                                    if (last?.role == "assistant") {
                                        history[history.lastIndex] = last.copy(
                                            deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
                                        )
                                    }
                                    persistHistory()
                                    scheduleMemoryExtractionIfDue(
                                        providers = providersForRequest,
                                        memorySecret = memorySecretForThisRequest,
                                    )
                                }
                            }

                            override fun onFailure(failure: ChatStreamFailure) {
                                memoryEngine?.close()
                                memoryEngine = null
                                mainHandler.post {
                                    busy = false
                                    status = failure.safeMessage
                                    val last = history.lastOrNull()
                                    if (last?.role == "assistant") {
                                        history[history.lastIndex] = last.copy(
                                            content = last.content.ifBlank { failure.safeMessage },
                                            deliveryState = PersistedChatMessage.DeliveryState.FAILED,
                                        )
                                    }
                                    persistHistory()
                                }
                            }
                        }
                        coroutineScope.launch {
                            try {
                                val modelAssets = EmbeddingGemma2ModelAssetManager(File(context.filesDir, "models"))
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
                                    client.stream(messages, observer)
                                } else {
                                    val engine = LiteRtLmEmbeddingGemma2Engine(
                                        context = context.applicationContext,
                                        modelFile = installedModel.file,
                                        npuModelCompatible = installedModel.isQualcommSm8650Optimized,
                                        diagnostics = diagnosticStore,
                                    )
                                    memoryEngine = engine
                                    // Native model loading can block; never initialize the runtime on Compose's main thread.
                                    withContext(Dispatchers.IO) { engine.initialize() }
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
                                    val memoryTextStore = AtomicMemoryTextStore(context)
                                    val vectorSnapshotStore = AtomicVectorSnapshotStore(context)
                                    var indexMaintenanceMessage = ""
                                    when (val syncResult = withContext(Dispatchers.IO) {
                                        TemporalFactTextSynchronizer(
                                            factStore = AtomicTemporalFactStore(context.applicationContext),
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
                                                        status = "检测到记忆事实已更新，正在刷新本地向量索引……"
                                                        val rebuildResult = withContext(Dispatchers.IO) {
                                                            MemoryIndexRebuilder(
                                                                runtime = memoryRuntime,
                                                                saveSourceTexts = memoryTextStore::save,
                                                                saveSnapshot = vectorSnapshotStore::save,
                                                            ).rebuild(sourceTexts.records)
                                                        }
                                                        when (rebuildResult) {
                                                            is MemoryIndexRebuildResult.Success -> {
                                                                diagnosticStore.info(
                                                                    "memory.index.refresh_succeeded",
                                                                    "records=${rebuildResult.indexedRecords}; dimensions=${rebuildResult.dimensions}",
                                                                )
                                                                indexMaintenanceMessage = "索引已刷新：${rebuildResult.indexedRecords} 条，${rebuildResult.dimensions} 维。"
                                                            }
                                                            is MemoryIndexRebuildResult.Failed -> {
                                                                diagnosticStore.warn(
                                                                    "memory.index.refresh_failed",
                                                                    "stage=${rebuildResult.stage}; reason=${rebuildResult.reason}",
                                                                )
                                                                indexMaintenanceMessage = "索引刷新失败（${rebuildResult.stage}）；保留可用数据并允许下次重试。"
                                                            }
                                                        }
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
                                                        status = "检测到向量索引缺失或维度变化，正在重建本地记忆索引……"
                                                        val rebuildResult = withContext(Dispatchers.IO) {
                                                            MemoryIndexRebuilder(
                                                                runtime = memoryRuntime,
                                                                saveSourceTexts = memoryTextStore::save,
                                                                saveSnapshot = vectorSnapshotStore::save,
                                                            ).rebuild(sourceTexts.records)
                                                        }
                                                        when (rebuildResult) {
                                                            is MemoryIndexRebuildResult.Success -> {
                                                                diagnosticStore.info(
                                                                    "memory.index.rebuild.succeeded",
                                                                    "records=${rebuildResult.indexedRecords}; dimensions=${rebuildResult.dimensions}; model=${rebuildResult.modelId}",
                                                                )
                                                                indexMaintenanceMessage = "索引已重建：${rebuildResult.indexedRecords} 条，${rebuildResult.dimensions} 维。"
                                                            }
                                                            is MemoryIndexRebuildResult.Failed -> {
                                                                diagnosticStore.warn(
                                                                    "memory.index.rebuild.failed",
                                                                    "stage=${rebuildResult.stage}; reason=${rebuildResult.reason}",
                                                                )
                                                                indexMaintenanceMessage = "索引重建失败（${rebuildResult.stage}）；不会伪报重建成功。"
                                                            }
                                                        }
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
                                    MemoryAwareChatRequest(client, memoryContext).stream(messages, observer)
                                }
                            } catch (error: Exception) {
                                diagnosticStore.warn(
                                    "chat.embedding.fallback",
                                    "reason=${error::class.java.simpleName}",
                                )
                                memoryEngine?.close()
                                memoryEngine = null
                                status = "本地记忆不可用，已尝试回退普通聊天：${error.message?.take(120) ?: error::class.java.simpleName}"
                                try {
                                    client.stream(messages, observer)
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
                    enabled = !busy && historyReady,
                ) { Text(if (!historyReady) "恢复记录中…" else if (busy) "生成中…" else "发送消息") }
                Button(
                    onClick = {
                        history.clear()
                        context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE).edit()
                            .remove("memory_extraction_last_success_turn_id")
                            .putString("memory_extraction_conversation_id", UUID.randomUUID().toString())
                            .apply()
                        persistHistory()
                        status = "会话已清空；长期事实记忆保留，新的对话将单独记录来源。"
                    },
                    enabled = !busy && historyReady && history.isNotEmpty(),
                ) { Text("清空会话") }
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
