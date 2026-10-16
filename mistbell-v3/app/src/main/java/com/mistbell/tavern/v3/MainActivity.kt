package com.mistbell.tavern.v3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.Toast
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mistbell.tavern.v3.core.inference.BackendEvidence
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2ModelAssetManager
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2RuntimeAdapter
import com.mistbell.tavern.v3.feature.embedding.LiteRtLmEmbeddingGemma2Engine
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus

private data class BuildStage(val title: String, val detail: String)

private val initialStages = listOf(
    BuildStage("聊天与 API 核心", "已建立独立流式聊天客户端与提示词组装基础；端到端集成仍需继续验证。"),
    BuildStage("世界书与角色卡", "按 3.0 专属格式重新实现，并为旧格式编写独立导入器；尚未完成。"),
    BuildStage("记忆与本地向量", "已建立事实历史、批处理策略和向量快照基础；数据库接入、召回与重建仍需继续。"),
    BuildStage("本地推理与硬件状态", "已建立推理运行状态与 Embedding 输出校验契约；EmbeddingGemma 2 / LiteRT-LM 真机后端尚未验证。"),
    BuildStage("生图、安全与备份", "独立生图 API、凭据隔离、事务式备份恢复仍待实现。"),
)

class MainActivity : ComponentActivity() {
    private lateinit var diagnostics: DiagnosticLogStore
    private lateinit var modelAssets: EmbeddingGemma2ModelAssetManager
    private var activeEmbeddingEngine: LiteRtLmEmbeddingGemma2Engine? = null
    private var inferenceStatus by mutableStateOf(InferenceRuntimeStatus.idle())
    private var operationMessage by mutableStateOf("正在检查本地模型……")
    private var operationBusy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = DiagnosticLogStore(filesDir)
        modelAssets = EmbeddingGemma2ModelAssetManager(File(filesDir, "models"))
        diagnostics.info("app.started", "build=3.0.0-dev")
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) { modelAssets.verifyInstalled() }
            if (!operationBusy) operationMessage = if (installed == null) {
                "本地模型未安装，需先下载 EmbeddingGemma 2。"
            } else {
                "本地模型已校验：${installed.sizeBytes} bytes；SHA-256 一致。"
            }
            diagnostics.info("embedding.model.startup.check", "installed=${installed != null}")
        }
        val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                diagnostics.info("embedding.model.import.cancelled")
            } else if (operationBusy) {
                Toast.makeText(this, "当前操作尚未完成", Toast.LENGTH_LONG).show()
            } else {
                operationBusy = true
                operationMessage = "正在导入本地 .litertlm 模型……"
                lifecycleScope.launch {
                    try {
                        val sourceName = withContext(Dispatchers.IO) {
                            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                            }
                        } ?: throw IllegalStateException("无法读取所选文件名。")
                        require(
                            sourceName.endsWith(".litertlm", ignoreCase = true) &&
                                (sourceName.contains("embeddinggemma", ignoreCase = true) ||
                                    sourceName.contains("embedding-gemma", ignoreCase = true)),
                        ) {
                            "请选择文件名标明 EmbeddingGemma 的 .litertlm 模型文件。"
                        }
                        val input = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri) }
                            ?: throw IllegalStateException("所选文件无法打开。")
                        val imported = modelAssets.importModel(sourceName, input) { bytes, _ ->
                            runOnUiThread { operationMessage = "模型导入中：${bytes / (1024 * 1024)} MiB" }
                        }
                        operationMessage = "模型导入完成，SHA-256：${imported.sha256}"
                        diagnostics.info("embedding.model.import.succeeded", "bytes=${imported.sizeBytes}; sha256=${imported.sha256}")
                    } catch (cancelled: CancellationException) {
                        diagnostics.warn("embedding.model.import.cancelled")
                        throw cancelled
                    } catch (error: Exception) {
                        val detail = "${error::class.java.simpleName}: ${error.message ?: "model import failed"}"
                            .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                        operationMessage = "模型导入失败：$detail"
                        inferenceStatus = InferenceRuntimeStatus(
                            phase = InferencePhase.FAILED,
                            modelId = com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy.MODEL_ID,
                            backend = InferenceBackend.UNKNOWN,
                            runtimeId = null,
                            lastError = detail,
                        )
                        diagnostics.error("embedding.model.import.failed", detail)
                    } finally {
                        operationBusy = false
                    }
                }
            }
        }

        val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) {
                diagnostics.info("diagnostics.export.cancelled")
            } else {
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val output = contentResolver.openOutputStream(uri)
                                ?: throw IllegalStateException("Selected export destination could not be opened.")
                            output.use { diagnostics.exportTo(it) }
                        }
                        Toast.makeText(this@MainActivity, "诊断日志已导出", Toast.LENGTH_LONG).show()
                        diagnostics.info("diagnostics.export.succeeded")
                    } catch (error: Exception) {
                        diagnostics.error("diagnostics.export.failed", error::class.java.simpleName + ": " + (error.message ?: "unknown error"))
                        Toast.makeText(this@MainActivity, "日志导出失败，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DevelopmentHome(
                        inferenceStatus = inferenceStatus,
                        operationMessage = operationMessage,
                        operationBusy = operationBusy,
                        onDownloadModel = ::downloadEmbeddingModel,
                        onImportModel = { importModel.launch(arrayOf("*/*")) },
                        onRunEmbeddingTest = ::runEmbeddingSelfTest,
                        onExportLogs = { exportDiagnostics.launch("mistbell-diagnostics.txt") },
                    )
                }
            }
        }
    }

    private fun downloadEmbeddingModel() {
        if (operationBusy) return
        operationBusy = true
        operationMessage = "开始下载模型；请保持应用在前台并连接稳定网络。"
        diagnostics.info("embedding.model.download.started", "source=official-model-host")
        lifecycleScope.launch {
            try {
                val downloaded = modelAssets.download { bytes, total ->
                    runOnUiThread {
                        operationMessage = if (total == null) {
                            "模型下载中：${bytes / (1024 * 1024)} MiB"
                        } else {
                            "模型下载中：${(bytes * 100 / total).coerceIn(0, 100)}% (${bytes / (1024 * 1024)} MiB)"
                        }
                    }
                }
                operationMessage = "模型下载完成，SHA-256：${downloaded.sha256}"
                diagnostics.info(
                    "embedding.model.download.succeeded",
                    "bytes=${downloaded.sizeBytes}; sha256=${downloaded.sha256}",
                )
            } catch (cancelled: CancellationException) {
                diagnostics.warn("embedding.model.download.cancelled")
                throw cancelled
            } catch (error: Exception) {
                val detail = "${error::class.java.simpleName}: ${error.message ?: "download failed"}"
                    .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                operationMessage = "模型下载失败：$detail"
                inferenceStatus = InferenceRuntimeStatus(
                    phase = InferencePhase.FAILED,
                    modelId = com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy.MODEL_ID,
                    backend = InferenceBackend.UNKNOWN,
                    runtimeId = null,
                    lastError = detail,
                )
                diagnostics.error("embedding.model.download.failed", detail)
            } finally {
                operationBusy = false
            }
        }
    }

    private fun runEmbeddingSelfTest() {
        if (operationBusy) return
        operationBusy = true
        operationMessage = "正在准备本地 EmbeddingGemma 2 推理……"
        lifecycleScope.launch {
            var runtime: LiteRtLmEmbeddingGemma2Engine? = null
            try {
                val asset = withContext(Dispatchers.IO) { modelAssets.verifyInstalled() }
                    ?: throw IllegalStateException("模型不存在或 SHA-256 校验失败；请重新下载模型。")
                activeEmbeddingEngine?.close()
                val initializedRuntime = LiteRtLmEmbeddingGemma2Engine(
                    context = applicationContext,
                    modelFile = asset.file,
                    diagnostics = diagnostics,
                )
                runtime = initializedRuntime
                activeEmbeddingEngine = initializedRuntime
                inferenceStatus = InferenceRuntimeStatus(
                    phase = InferencePhase.LOADING_MODEL,
                    modelId = com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy.MODEL_ID,
                    backend = InferenceBackend.UNKNOWN,
                    runtimeId = initializedRuntime.runtimeId,
                )
                initializedRuntime.initialize()
                inferenceStatus = initializedRuntime.currentStatus()
                operationMessage = "模型已初始化，正在执行真实文本向量测试……"
                inferenceStatus = initializedRuntime.currentStatus().copy(phase = InferencePhase.RUNNING)
                val result = EmbeddingGemma2RuntimeAdapter(initializedRuntime).embedQueries(
                    listOf("Mistbell Tavern local embedding diagnostic test"),
                )
                inferenceStatus = result.status
                operationMessage =
                    "真实本地推理成功：${result.vectors.single().size} 维向量；配置后端 ${result.status.backend}。硬件实际调用仍需结合设备日志确认。"
                diagnostics.info(
                    "embedding.selftest.succeeded",
                    "dimensions=${result.vectors.single().size}; configuredBackend=${result.status.backend}; evidence=${result.status.backendEvidence}; elapsedMs=${result.status.lastExecutionMillis}",
                )
            } catch (cancelled: CancellationException) {
                diagnostics.warn("embedding.selftest.cancelled")
                throw cancelled
            } catch (error: Exception) {
                val detail = "${error::class.java.simpleName}: ${error.message ?: "embedding test failed"}"
                    .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                inferenceStatus = runtime?.currentStatus() ?: InferenceRuntimeStatus(
                    phase = InferencePhase.FAILED,
                    modelId = com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy.MODEL_ID,
                    backend = InferenceBackend.UNKNOWN,
                    runtimeId = null,
                    lastError = detail,
                )
                operationMessage = "本地推理测试失败：$detail"
                diagnostics.error("embedding.selftest.failed", detail)
            } finally {
                operationBusy = false
            }
        }
    }

    override fun onDestroy() {
        activeEmbeddingEngine?.close()
        activeEmbeddingEngine = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevelopmentHome(
    inferenceStatus: InferenceRuntimeStatus,
    operationMessage: String,
    operationBusy: Boolean,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    onRunEmbeddingTest: () -> Unit,
    onExportLogs: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mistbell Tavern 3.0")
                        Text(
                            text = "全新实现 · 开发基线",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("从零构建，不复用旧应用实现", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "这是独立的 3.0 工程。状态只反映已实现与已验证的内容，不把计划或依赖安装视为功能完成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { InferenceStatusCard(inferenceStatus) }
            item {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("本地模型与实测", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        AnimatedContent(targetState = operationMessage, label = "operation-message") { message ->
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onDownloadModel, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("下载 / 重新下载 EmbeddingGemma 2 模型")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onImportModel, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("从文件导入 .litertlm 模型")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRunEmbeddingTest, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("初始化后执行真实向量测试")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("诊断与故障排查", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "导出的日志包含运行阶段、后端初始化结果、错误摘要和耗时；不会主动记录聊天正文、提示词或 API 密钥。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onExportLogs, modifier = Modifier.fillMaxWidth()) {
                            Text("导出诊断日志")
                        }
                    }
                }
            }
            items(initialStages, key = BuildStage::title) { stage ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stage.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(stage.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun InferenceStatusCard(status: InferenceRuntimeStatus) {
    val backendLabel = when (status.backend) {
        InferenceBackend.NPU -> "NPU"
        InferenceBackend.GPU -> "GPU"
        InferenceBackend.CPU -> "CPU"
        InferenceBackend.UNKNOWN -> "未验证"
    }
    val phaseLabel = when (status.phase) {
        InferencePhase.NOT_INITIALIZED -> "尚未初始化"
        InferencePhase.LOADING_MODEL -> "正在加载模型"
        InferencePhase.READY -> "已就绪"
        InferencePhase.RUNNING -> "正在推理"
        InferencePhase.FALLBACK -> "已回退"
        InferencePhase.FAILED -> "初始化/推理失败"
    }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本地 Embedding / NPU 状态", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("$phaseLabel · 配置后端：$backendLabel", style = MaterialTheme.typography.bodyMedium)
            val details = listOfNotNull(
                status.modelId?.let { "模型：$it" },
                status.acceleratorName?.let { "配置后端：$it" },
                status.backendEvidence.takeIf { it != BackendEvidence.NOT_AVAILABLE }?.let { "硬件证据：${when (it) { BackendEvidence.NOT_AVAILABLE -> "无"; BackendEvidence.CONFIGURED_ONLY -> "仅配置成功，未独立确认实际硬件调用"; BackendEvidence.RUNTIME_REPORTED -> "运行时报告"; BackendEvidence.DEVICE_VERIFIED -> "设备实测确认" }}" },
                status.lastExecutionMillis?.let { "最近耗时：$it ms" },
                status.fallbackReason?.let { "回退原因：$it" },
                status.lastError?.let { "错误：$it" },
            )
            details.forEach { detail ->
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (status.phase == InferencePhase.NOT_INITIALIZED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前没有运行中的推理后端；不会虚报 NPU 已调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
