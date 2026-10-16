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
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextService
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.AtomicVectorSnapshotStore
import com.mistbell.tavern.v3.core.storage.PersistedVectorSnapshotProvider
import com.mistbell.tavern.v3.core.storage.VectorSnapshotLoadResult
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2MemoryRuntime
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2ModelAssetManager
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2RuntimeAdapter
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
    var baseUrl by remember { mutableStateOf("https://api.odys.work/v1") }
    var modelId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
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
    val context = LocalContext.current
    val historyStore = remember(context) { ChatHistoryStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    val diagnosticStore = remember(context) { DiagnosticLogStore(context.applicationContext.filesDir) }

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
                        onValueChange = { baseUrl = it },
                        label = { Text("API Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
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
                        "当前为开发测试入口：API 配置不会持久保存；离开此页面后密钥会从界面状态中清除。",
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
                        val providers = try {
                            ProviderProfileSet(
                                profiles = listOf(profile),
                                selectedProfileIds = mapOf(ProviderKind.CHAT to profile.id),
                            )
                        } catch (_: IllegalArgumentException) {
                            status = "API 配置无效，请检查 Base URL 和模型 ID。"
                            return@Button
                        }
                        val secretForThisRequest = apiKey
                        val client = OpenAiCompatibleChatClient(
                            providers = providers,
                            credentials = CredentialResolver { handle ->
                                if (handle == "session-chat-key") secretForThisRequest else null
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
                                if (installedModel == null || configuredDimensions !in CHAT_EMBEDDING_DIMENSION_OPTIONS || configuredDimensions > 768) {
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
                                    when (
                                        val existingSnapshot = withContext(Dispatchers.IO) {
                                            vectorSnapshotStore.load(memoryRuntime.modelId, memoryRuntime.dimensions)
                                        }
                                    ) {
                                        is VectorSnapshotLoadResult.Loaded -> Unit
                                        is VectorSnapshotLoadResult.Corrupt -> {
                                            diagnosticStore.warn(
                                                "memory.index.rebuild_skipped",
                                                "reason=corrupt_snapshot_preserved",
                                            )
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
                                                                status = "本地记忆索引已重建：${rebuildResult.indexedRecords} 条，${rebuildResult.dimensions} 维。"
                                                            }
                                                            is MemoryIndexRebuildResult.Failed -> {
                                                                diagnosticStore.warn(
                                                                    "memory.index.rebuild.failed",
                                                                    "stage=${rebuildResult.stage}; reason=${rebuildResult.reason}",
                                                                )
                                                                status = "本地记忆索引重建失败（${rebuildResult.stage}）；继续普通聊天或使用可用记忆。"
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
                                    status = "本地记忆检索已接入；后端=${backendStatus.backend}，证据=${backendStatus.backendEvidence}。配置状态不等于芯片级 NPU 实测。"
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
                        persistHistory()
                        status = "会话已清空。"
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
