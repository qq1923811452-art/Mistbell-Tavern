package com.mistbell.tavern.v3

import android.os.Bundle
import android.os.Build
import android.net.Uri
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
import com.mistbell.tavern.v3.feature.embedding.DownloadedEmbeddingModel
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2RuntimeAdapter
import com.mistbell.tavern.v3.feature.embedding.LiteRtLmEmbeddingGemma2Engine
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import com.mistbell.tavern.v3.feature.chat.ChatScreen

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
    private var showChat by mutableStateOf(true)
    private val embeddingDimensionOptions = listOf(128, 256, 512, 768, 1024, 1536, 2048)
    private var selectedEmbeddingDimensions by mutableStateOf(768)
    private var qualcommModelSelected by mutableStateOf(false)
    private var modelProfileGeneration = 0

    /**
     * Some Android document providers expose a readable filesystem path (notably MediaStore
     * entries in Downloads). Use it when available so selecting an existing model does not
     * force a second copy. Providers that expose only a content URI use the private staging
     * fallback because LiteRT-LM's model API currently requires a filesystem path.
     */
    @Suppress("DEPRECATION")
    private fun resolveReadableModelFile(uri: Uri): File? = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (!cursor.moveToFirst() || column < 0) return@use null
            cursor.getString(column)?.takeIf(String::isNotBlank)?.let(::File)
                ?.takeIf { it.isFile && it.canRead() }
        }
    }.getOrNull()

    private fun createModelAssetManager(qualcomm: Boolean) =
        EmbeddingGemma2ModelAssetManager(
            modelDirectory = File(filesDir, "models"),
            preferQualcommSm8650Model = qualcomm,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = DiagnosticLogStore(filesDir)
        val settings = getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
        selectedEmbeddingDimensions = settings
            .getInt("embedding_output_dimensions", 768)
            .takeIf { it in embeddingDimensionOptions } ?: 768
        qualcommModelSelected = settings.getBoolean("embedding_qualcomm_sm8650_model", false)
        modelAssets = createModelAssetManager(qualcommModelSelected)
        diagnostics.info("app.started", "build=3.0.0-dev")
        lifecycleScope.launch {
            val profileAtStart = qualcommModelSelected
            val assetsAtStart = modelAssets
            val generationAtStart = modelProfileGeneration
            var installed = withContext(Dispatchers.IO) { assetsAtStart.verifyInstalled() }
            if (installed == null) {
                try {
                    restorePublicModelIfPresent(profileAtStart)
                } catch (error: Exception) {
                    val detail = "${error::class.java.simpleName}: ${error.message ?: "public model restore failed"}"
                        .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                    diagnostics.error("embedding.model.restore.failed", "profile=${if (profileAtStart) "QUALCOMM_SM8650" else "PORTABLE"}; $detail")
                    if (generationAtStart == modelProfileGeneration) {
                        operationMessage = "公共模型恢复失败：$detail"
                    }
                }
                installed = withContext(Dispatchers.IO) { assetsAtStart.verifyInstalled() }
            }
            if (generationAtStart == modelProfileGeneration && !operationBusy) {
                operationMessage = if (installed == null) {
                    "所选模型尚未安装或校验失败。下载位置：${publicModelDirectoryLabel()}"
                } else {
                    "本地模型已校验：${installed.sizeBytes} bytes；SHA-256 一致。公共目录：${publicModelDirectoryLabel()}"
                }
            }
            diagnostics.info(
                "embedding.model.startup.check",
                "profile=${if (profileAtStart) "QUALCOMM_SM8650" else "PORTABLE"}; installed=${installed != null}; stale=${generationAtStart != modelProfileGeneration}",
            )
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
                        operationMessage = "正在验证并复制到软件内部目录……"
                        val imported = modelAssets.importModel(sourceName, input) { bytes, _ ->
                            runOnUiThread { operationMessage = "模型导入中：${bytes / (1024 * 1024)} MiB" }
                        }
                        val settings = getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
                        val profileKey = if (qualcommModelSelected) "embedding_public_model_name_qualcomm" else "embedding_public_model_name_portable"
                        val localPathKey = if (qualcommModelSelected) "embedding_local_model_path_qualcomm" else "embedding_local_model_path_portable"
                        settings.edit()
                            .remove(localPathKey)
                            .remove(profileKey)
                            .apply()
                        operationMessage = "模型已校验并复制到软件内部目录：${imported.file.absolutePath}\nSHA-256：${imported.sha256}"
                        diagnostics.info("embedding.model.import.private_copy", "bytes=${imported.sizeBytes}; sha256=${imported.sha256}")
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
                    if (showChat) {
                        ChatScreen(onBack = { showChat = false })
                    } else {
                        DevelopmentHome(
                            inferenceStatus = inferenceStatus,
                            operationMessage = operationMessage,
                            operationBusy = operationBusy,
                            onOpenChat = { showChat = true },
                            onDownloadModel = ::downloadEmbeddingModel,
                            onImportModel = { importModel.launch(arrayOf("*/*")) },
                            qualcommModelSelected = qualcommModelSelected,
                            onSelectModelProfile = ::selectModelProfile,
                            selectedEmbeddingDimensions = selectedEmbeddingDimensions,
                            onCycleEmbeddingDimensions = ::cycleEmbeddingDimensions,
                            onRunEmbeddingTest = ::runEmbeddingSelfTest,
                            onExportLogs = { exportDiagnostics.launch("mistbell-diagnostics.txt") },
                        )
                    }
                }
            }
        }
    }

    private fun selectModelProfile(qualcomm: Boolean) {
        if (operationBusy || qualcommModelSelected == qualcomm) return
        activeEmbeddingEngine?.close()
        activeEmbeddingEngine = null
        qualcommModelSelected = qualcomm
        modelProfileGeneration += 1
        val generationAtSelection = modelProfileGeneration
        val assetsAtSelection = createModelAssetManager(qualcomm)
        modelAssets = assetsAtSelection
        getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE).edit()
            .putBoolean("embedding_qualcomm_sm8650_model", qualcomm)
            .apply()
        diagnostics.info(
            "embedding.model.profile.selected",
            "profile=${if (qualcomm) "QUALCOMM_SM8650" else "PORTABLE"}",
        )
        operationMessage = if (qualcomm) {
            "已选择 Qualcomm SM8650 专用模型。注意：选择专用模型不代表 NPU 运行库已就绪；下载后仍需实测。"
        } else {
            "已选择通用模型，适合先进行 CPU/GPU 兼容性测试。"
        }
        // Keep model selection, restore, download/import and self-test mutually exclusive.
        // Without this guard, another operation could verify/read a model while restore atomically
        // replaces its private copy, or a second profile selection could start a competing restore.
        operationBusy = true
        lifecycleScope.launch {
            try {
                try {
                    restorePublicModelIfPresent(qualcomm)
                } catch (error: Exception) {
                    val detail = "${error::class.java.simpleName}: ${error.message ?: "public model restore failed"}"
                        .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                    diagnostics.error("embedding.model.restore.failed", "profile=${if (qualcomm) "QUALCOMM_SM8650" else "PORTABLE"}; $detail")
                    if (generationAtSelection == modelProfileGeneration) {
                        operationMessage = "公共模型恢复失败：$detail"
                    }
                }
                val installed = withContext(Dispatchers.IO) { assetsAtSelection.verifyInstalled() }
                if (generationAtSelection == modelProfileGeneration) {
                    if (installed != null) {
                        operationMessage = "所选模型已从公共目录恢复并校验：${publicModelDirectoryLabel()}"
                    } else if (!operationMessage.startsWith("公共模型恢复失败：")) {
                        operationMessage += " 尚未找到已安装的此版本，可点击下载。"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = "${error::class.java.simpleName}: ${error.message ?: "model profile verification failed"}"
                    .take(InferenceRuntimeStatus.MAX_DETAIL_LENGTH)
                diagnostics.error("embedding.model.profile.verify.failed", "profile=${if (qualcomm) "QUALCOMM_SM8650" else "PORTABLE"}; $detail")
                if (generationAtSelection == modelProfileGeneration) {
                    operationMessage = "模型配置检查失败：$detail"
                }
            } finally {
                if (generationAtSelection == modelProfileGeneration) operationBusy = false
            }
        }
    }

    private fun downloadEmbeddingModel() {
        if (operationBusy) return
        operationBusy = true
        operationMessage = "开始下载模型；校验后将保存到 ${publicModelDirectoryLabel()}；请保持应用在前台并连接稳定网络。"
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
                getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE).edit()
                    .remove(if (qualcommModelSelected) "embedding_local_model_path_qualcomm" else "embedding_local_model_path_portable")
                    .remove(if (qualcommModelSelected) "embedding_public_model_name_qualcomm" else "embedding_public_model_name_portable")
                    .apply()
                operationMessage = "模型已下载、校验并保存到软件内部目录：${downloaded.file.absolutePath}\nSHA-256：${downloaded.sha256}"
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

    private fun cycleEmbeddingDimensions() {
        val currentIndex = embeddingDimensionOptions.indexOf(selectedEmbeddingDimensions).coerceAtLeast(0)
        selectedEmbeddingDimensions = embeddingDimensionOptions[(currentIndex + 1) % embeddingDimensionOptions.size]
        getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE).edit()
            .putInt("embedding_output_dimensions", selectedEmbeddingDimensions)
            .apply()
        diagnostics.info("embedding.dimensions.changed", "dimensions=$selectedEmbeddingDimensions")
        operationMessage = "已选择 ${selectedEmbeddingDimensions} 维输出；下次本地向量测试将使用此档位。已有向量需要按新维度重建后才能混合检索。"
    }

    private fun runEmbeddingSelfTest() {
        if (operationBusy) return
        if (selectedEmbeddingDimensions > com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) {
            operationMessage = "当前 EmbeddingGemma 2 原生最高支持 768 维；已保留 ${selectedEmbeddingDimensions} 维选项，但需要切换到原生支持该维度的模型后才能测试。未启动不兼容的推理任务。"
            diagnostics.warn("embedding.selftest.unsupported_dimension_for_model", "model=EmbeddingGemma2; nativeDimensions=768; selectedDimensions=$selectedEmbeddingDimensions")
            return
        }
        operationBusy = true
        operationMessage = "正在准备本地 EmbeddingGemma 2 推理……"
        lifecycleScope.launch {
            var runtime: LiteRtLmEmbeddingGemma2Engine? = null
            var verifiedAsset: DownloadedEmbeddingModel? = null
            try {
                val asset = withContext(Dispatchers.IO) { modelAssets.verifyInstalled() }
                    ?: throw IllegalStateException("模型不存在或 SHA-256 校验失败；请重新下载模型。")
                verifiedAsset = asset
                activeEmbeddingEngine?.close()
                val initializedRuntime = LiteRtLmEmbeddingGemma2Engine(
                    context = applicationContext,
                    modelFile = asset.file,
                    npuModelCompatible = asset.isQualcommSm8650Optimized,
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
                val result = EmbeddingGemma2RuntimeAdapter(
                    initializedRuntime,
                    outputDimensions = selectedEmbeddingDimensions,
                ).embedQueries(
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
                val blockedByUnverifiedQualcommRuntime =
                    verifiedAsset?.isQualcommSm8650Optimized == true &&
                        error is IllegalArgumentException &&
                        error.message.orEmpty().contains("requires a verified NPU runtime", ignoreCase = true)
                if (blockedByUnverifiedQualcommRuntime) {
                    operationMessage =
                        "Qualcomm 专用模型已导入且 SHA-256 校验通过，但当前 APK 没有经过验证的 NPU 运行库，因此暂时不能执行该模型。已导入模型不会被删除。请切换到通用版，下载/导入通用模型后进行 CPU/GPU 测试；Qualcomm 专用模型仍保留在公共下载目录。"
                    diagnostics.warn(
                        "embedding.selftest.blocked.npu_runtime_unverified",
                        "modelSha256=${verifiedAsset?.sha256.orEmpty()}; action=select_portable_model_for_cpu_gpu_test",
                    )
                } else {
                    operationMessage = "本地推理测试失败：$detail"
                    diagnostics.error("embedding.selftest.failed", detail)
                }
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
    onOpenChat: () -> Unit,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    qualcommModelSelected: Boolean,
    onSelectModelProfile: (Boolean) -> Unit,
    selectedEmbeddingDimensions: Int,
    onCycleEmbeddingDimensions: () -> Unit,
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
                        Text("聊天与 API", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("进入独立聊天测试页面，配置 OpenAI 兼容 API 并验证流式回复。API 密钥仅保存在当前页面的内存状态中。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onOpenChat, modifier = Modifier.fillMaxWidth()) {
                            Text("进入聊天")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("本地模型与实测", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("模型保存位置：软件内部私有目录（无需公共目录副本）", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("请选择要下载的模型版本：", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { onSelectModelProfile(false) },
                            enabled = !operationBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (!qualcommModelSelected) "✓ 已选：通用版（约 485 MB）" else "选择通用版（约 485 MB）")
                        }
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { onSelectModelProfile(true) },
                            enabled = !operationBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (qualcommModelSelected) "✓ 已选：Qualcomm SM8650 专用版（约 563 MB）" else "选择 Qualcomm SM8650 专用版（约 563 MB）")
                        }
                        Text(
                            "专用版针对骁龙 8 Gen 3/SM8650；NPU 是否可用仍取决于 APK 中的运行库与真机验证。模型校验后只保存在软件内部目录，不会另存到公共下载目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("向量维度档位：${selectedEmbeddingDimensions} 维", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onCycleEmbeddingDimensions, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("切换向量维度（128 → 256 → 512 → 768 → 1024 → 1536 → 2048）")
                        }
                        Text("EmbeddingGemma 2 原生最高为 768 维，768 档可直接使用；1024 / 1536 / 2048 档为未来兼容更高维模型预留，当前选择这些档位时会明确提示模型不兼容，不会伪造高维向量。设置保存在本机；更换维度后需要重建旧向量索引。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        InferencePhase.INITIALIZED -> "已初始化，尚未验证真实推理"
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
