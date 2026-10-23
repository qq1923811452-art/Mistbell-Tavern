@file:Suppress("LargeClass")

package com.mistbell.tavern.v3

import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
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
import com.mistbell.tavern.v3.feature.embedding.EmbeddingEngineProcessHolder
import com.mistbell.tavern.v3.feature.embedding.EmbeddingEngineProcessHolder.CloseReason
import com.mistbell.tavern.v3.feature.embedding.EmbeddingVectorCache
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import com.mistbell.tavern.v3.feature.about.LicensesScreen
import com.mistbell.tavern.v3.feature.chat.ChatScreen
import com.mistbell.tavern.v3.feature.character.CharacterCardLibraryScreen
import com.mistbell.tavern.v3.feature.worldbook.WorldBookLibraryScreen
import com.mistbell.tavern.v3.feature.image.ImageGenerationScreen
import com.mistbell.tavern.v3.feature.settings.SettingsHomeScreen
import com.mistbell.tavern.v3.ui.TavernBackground
import com.mistbell.tavern.v3.ui.TavernBottomBar
import com.mistbell.tavern.v3.ui.TavernTab

private data class BuildStage(val title: String, val detail: String)

private val initialStages = listOf(
    BuildStage("聊天与 API 核心", "独立流式聊天客户端、API 路由、Token 用量和提示词组装已接入；仍需收尾边界场景与端到端验收。"),
    BuildStage("世界书与角色卡", "属于独立的 PR #60 范围；本 PR 不重复实现，需以 PR #60 的实际合并与验收状态为准。"),
    BuildStage("记忆与本地向量", "已接入按角色/会话隔离的本地文本与向量存储、检索、时间事实同步及后台索引重建；仍需完成边界测试与规模性能验收。"),
    BuildStage("本地推理与硬件状态", "EmbeddingGemma 2 / LiteRT-LM 接入、后端状态与 Qualcomm 运行时打包流程已建立；CI 打包成功不代表最新 APK 的 CPU/GPU/NPU 真机验收完成。"),
    BuildStage("生图、安全与备份", "独立生图 API、独立密钥输入、远程图片显式下载、本地图片库及导出已接入；完整事务式备份/恢复流程仍需收尾。"),
)

class MainActivity : ComponentActivity() {
    private lateinit var diagnostics: DiagnosticLogStore
    private lateinit var modelAssets: EmbeddingGemma2ModelAssetManager
    private var inferenceStatus by mutableStateOf(InferenceRuntimeStatus.idle())
    private var operationMessage by mutableStateOf("正在检查本地模型……")
    private var operationBusy by mutableStateOf(false)
    private var showImageGeneration by mutableStateOf(false)
    private var showLicenses by mutableStateOf(false)
    private var mainTab by mutableStateOf(TavernTab.CHAT)
    private var chatSettingsMode by mutableStateOf(false)
    private var showLocalModel by mutableStateOf(false)
    private var contextTokens by mutableStateOf(
        getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE).getInt("chat_context_tokens", 32_000),
    )
    private var memoryEnabled by mutableStateOf(
        getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
            .getBoolean("memory_extraction_enabled", true),
    )
    private var showCharacterLibrary by mutableStateOf(false)
    private var showWorldBookLibrary by mutableStateOf(false)
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
            val installed = withContext(Dispatchers.IO) { assetsAtStart.verifyInstalled() }
            if (generationAtStart == modelProfileGeneration && !operationBusy) {
                operationMessage = if (installed == null) {
                    "软件内部尚未安装所选模型。可联网下载（自动备份到公共目录），或从公共目录选择已有模型导入。"
                } else {
                    "软件内部模型已校验：${installed.sizeBytes} bytes；SHA-256 一致。公共备份目录：${publicModelDirectoryLabel()}"
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
                        } ?: error("无法读取所选文件名。")
                        require(
                            sourceName.endsWith(".litertlm", ignoreCase = true) &&
                                (sourceName.contains("embeddinggemma", ignoreCase = true) ||
                                    sourceName.contains("embedding-gemma", ignoreCase = true)),
                        ) {
                            "请选择文件名标明 EmbeddingGemma 的 .litertlm 模型文件。"
                        }
                        val input = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri) }
                            ?: error("所选文件无法打开。")
                        operationMessage = "正在验证并复制到软件内部目录……"
                        val imported = modelAssets.importModel(sourceName, input) { bytes, _ ->
                            runOnUiThread { operationMessage = "模型导入中：${bytes / (1024 * 1024)} MiB" }
                        }
                        EmbeddingEngineProcessHolder.close(CloseReason.MODEL_CHANGED)
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

        val exportNpuEvidence = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) {
                diagnostics.info("npu.evidence.export.cancelled")
            } else {
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val directory = File(filesDir, "npu-evidence")
                            val latest = directory.listFiles { file ->
                                file.name.startsWith("npu-evidence-") && file.name.endsWith(".json")
                            }.orEmpty().maxByOrNull(File::lastModified)
                                ?: error("No NPU evidence file exists yet.")
                            val output = contentResolver.openOutputStream(uri)
                                ?: error("Selected export destination could not be opened.")
                            output.use { target -> latest.inputStream().use { it.copyTo(target) } }
                        }
                        diagnostics.info("npu.evidence.export.succeeded")
                        Toast.makeText(this@MainActivity, "NPU 证据 JSON 已导出", Toast.LENGTH_LONG).show()
                    } catch (error: Exception) {
                        diagnostics.warn("npu.evidence.export.failed", error::class.java.simpleName)
                        Toast.makeText(this@MainActivity, "暂无可导出的 NPU 证据，或导出失败", Toast.LENGTH_LONG).show()
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
                                ?: error("Selected export destination could not be opened.")
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
                    if (showLicenses) {
                        LicensesScreen(onBack = { showLicenses = false })
                    } else if (showImageGeneration) {
                        ImageGenerationScreen(onBack = { showImageGeneration = false })
                    } else if (showCharacterLibrary) {
                        CharacterCardLibraryScreen(onBack = { showCharacterLibrary = false })
                    } else if (showWorldBookLibrary) {
                        WorldBookLibraryScreen(onBack = { showWorldBookLibrary = false })
                    } else if (showLocalModel) {
                        TavernBackground {
                            DevelopmentHome(
                                inferenceStatus = inferenceStatus,
                                operationMessage = operationMessage,
                                operationBusy = operationBusy,
                                onOpenChat = { showLocalModel = false; mainTab = TavernTab.CHAT },
                                onOpenChatSettings = { showLocalModel = false; chatSettingsMode = true },
                                onOpenImageGeneration = { showLocalModel = false; showImageGeneration = true },
                                onOpenCharacterLibrary = { showLocalModel = false; showCharacterLibrary = true },
                                onOpenWorldBookLibrary = { showLocalModel = false; showWorldBookLibrary = true },
                                onOpenLicenses = { showLicenses = true },
                                onDownloadModel = ::downloadEmbeddingModel,
                                onImportModel = { importModel.launch(arrayOf("*/*")) },
                                qualcommModelSelected = qualcommModelSelected,
                                onSelectModelProfile = ::selectModelProfile,
                                selectedEmbeddingDimensions = selectedEmbeddingDimensions,
                                onCycleEmbeddingDimensions = ::cycleEmbeddingDimensions,
                                onRunEmbeddingTest = { runEmbeddingSelfTest() },
                                onRunNpuDiagnosticProbe = { runEmbeddingSelfTest(allowDiagnosticNpuProbe = true) },
                                onRunAlternateBackendBenchmark = { runAlternateBackendBenchmark() },
                                onExportNpuEvidence = { exportNpuEvidence.launch("npu-evidence.json") },
                                onExportLogs = { exportDiagnostics.launch("mistbell-diagnostics.txt") },
                            )
                        }
                    } else if (chatSettingsMode) {
                        TavernBackground {
                            ChatScreen(onBack = { chatSettingsMode = false }, settingsMode = true)
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                when (mainTab) {
                                    TavernTab.CHAT -> TavernBackground {
                                        ChatScreen(
                                            onBack = { mainTab = TavernTab.SETTINGS },
                                            onOpenChatSettings = { chatSettingsMode = true },
                                        )
                                    }
                                    TavernTab.SETTINGS -> Box(Modifier.fillMaxSize()) {
                                        Image(
                                            painter = painterResource(R.drawable.bg_settings),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.55f)),
                                        )
                                        SettingsHomeScreen(
                                            apiProfileLabel = runCatching {
                                                val prefs = getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
                                                val model = prefs.getString("chat_model_id", "").orEmpty()
                                                val url = prefs.getString("chat_api_base_url", "").orEmpty()
                                                listOf(model, url).filter { it.isNotBlank() }
                                                    .joinToString(" · ").ifBlank { "未配置" }
                                            }.getOrDefault("未配置"),
                                            contextTokens = contextTokens,
                                            memoryEnabled = memoryEnabled,
                                            onContextTokensChange = { tokens ->
                                                getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
                                                    .edit()
                                                    .putInt("chat_context_tokens", tokens)
                                                    .putString(
                                                        "chat_history_max_chars",
                                                        (tokens * 2).coerceIn(4_000, 100_000).toString(),
                                                    )
                                                    .apply()
                                                contextTokens = tokens
                                            },
                                            onMemoryEnabledChange = { enabled ->
                                                getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
                                                    .edit()
                                                    .putBoolean("memory_extraction_enabled", enabled)
                                                    .apply()
                                                memoryEnabled = enabled
                                            },
                                            onOpenApiSettings = { chatSettingsMode = true },
                                            onOpenEmbedding = { chatSettingsMode = true },
                                            onOpenChatSettings = { chatSettingsMode = true },
                                            onOpenImage = { showImageGeneration = true },
                                            onOpenCharacters = { showCharacterLibrary = true },
                                            onOpenWorldBook = { showWorldBookLibrary = true },
                                            onOpenLocalModel = { showLocalModel = true },
                                            onOpenLicenses = { showLicenses = true },
                                        )
                                    }
                                }
                            }
                            TavernBottomBar(
                                selected = mainTab,
                                onSelect = { mainTab = it },
                                modifier = Modifier.navigationBarsPadding(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun selectModelProfile(qualcomm: Boolean) {
        if (operationBusy || qualcommModelSelected == qualcomm) return
        EmbeddingEngineProcessHolder.close(CloseReason.MODEL_CHANGED)
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
                val installed = withContext(Dispatchers.IO) { assetsAtSelection.verifyInstalled() }
                if (generationAtSelection == modelProfileGeneration) {
                    if (installed != null) {
                        operationMessage = "所选模型已在软件内部目录校验通过。公共目录备份仍保留：${publicModelDirectoryLabel()}"
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

    private data class PublicModelBackup(val displayName: String, val reused: Boolean)

    private fun publicModelDirectoryLabel(): String =
        "Download/向量模型/"

    /** Saves a second, verified copy to public Downloads without overwriting existing files. */
    private suspend fun backupModelToPublicDownloads(model: DownloadedEmbeddingModel): PublicModelBackup =
        withContext(Dispatchers.IO) {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "自动公共目录备份需要 Android 10 或更高版本；内部模型已安装，但备份未完成。"
            }
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/向量模型/"
            val resolver = contentResolver
            val baseName = model.file.name
            var candidateIndex = 0
            while (candidateIndex < 100) {
                val candidateName = if (candidateIndex == 0) baseName else {
                    val extension = baseName.substringAfterLast('.', "")
                    val stem = baseName.substringBeforeLast('.', baseName)
                    "$stem-backup-$candidateIndex.$extension"
                }
                val existing = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(candidateName, relativePath),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)).toString(),
                    ) else null
                }
                if (existing != null) {
                    val existingHash = resolver.openInputStream(existing)?.use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (existingHash.equals(model.sha256, ignoreCase = true)) {
                        return@withContext PublicModelBackup(candidateName, reused = true)
                    }
                    candidateIndex++
                    continue
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, candidateName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法在公共目录创建模型备份文件。")
                try {
                    resolver.openOutputStream(destination, "w")?.use { output ->
                        FileInputStream(model.file).use { input -> input.copyTo(output) }
                    } ?: error("无法写入公共目录模型备份。")
                    val savedHash = resolver.openInputStream(destination)?.use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    check(savedHash.equals(model.sha256, ignoreCase = true)) { "公共目录备份写入后 SHA-256 校验失败。" }
                    resolver.update(destination, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                    return@withContext PublicModelBackup(candidateName, reused = false)
                } catch (error: Exception) {
                    resolver.delete(destination, null, null)
                    throw error
                }
            }
            error("公共目录存在过多同名模型备份，未覆盖任何现有文件。")
        }
    private fun downloadEmbeddingModel() {
        if (operationBusy) return
        operationBusy = true
        operationMessage = "开始下载模型；校验后保存到软件内部目录，并备份到 ${publicModelDirectoryLabel()}；请保持应用在前台并连接稳定网络。"
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
                EmbeddingEngineProcessHolder.close(CloseReason.MODEL_CHANGED)
                getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE).edit()
                    .remove(if (qualcommModelSelected) "embedding_local_model_path_qualcomm" else "embedding_local_model_path_portable")
                    .remove(if (qualcommModelSelected) "embedding_public_model_name_qualcomm" else "embedding_public_model_name_portable")
                    .apply()
                val backup = withContext(Dispatchers.IO) { backupModelToPublicDownloads(downloaded) }
                operationMessage = "模型已校验并安装到软件内部目录：${downloaded.file.absolutePath}\n公共目录备份：${backup.displayName}\nSHA-256：${downloaded.sha256}"
                diagnostics.info(
                    "embedding.model.download.succeeded",
                    "bytes=${downloaded.sizeBytes}; sha256=${downloaded.sha256}; publicBackup=${backup.displayName}; reused=${backup.reused}",
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

    private fun runAlternateBackendBenchmark() {
        if (operationBusy) return
        if (!qualcommModelSelected) {
            operationMessage = "GPU/CPU 对照实验需要先选择 Qualcomm SM8650 专用模型。"
            return
        }
        operationBusy = true
        operationMessage = "正在尝试用同一 Qualcomm 模型执行 GPU/CPU 基准；这是隔离实验，不改变生产回退策略……"
        lifecycleScope.launch {
            val summaries = mutableListOf<String>()
            try {
                val asset = withContext(Dispatchers.IO) { modelAssets.verifyInstalled() }
                    ?: error("模型不存在或 SHA-256 校验失败。")
                check(asset.isQualcommSm8650Optimized) { "当前安装模型不是 Qualcomm SM8650 专用模型。" }
                for (backend in listOf(InferenceBackend.GPU, InferenceBackend.CPU)) {
                    var benchmark: LiteRtLmEmbeddingGemma2Engine? = null
                    try {
                        benchmark = withContext(Dispatchers.IO) {
                            LiteRtLmEmbeddingGemma2Engine(
                                context = applicationContext,
                                modelFile = asset.file,
                                preferredBackends = listOf(backend),
                                npuModelCompatible = true,
                                diagnostics = diagnostics,
                                allowDiagnosticAlternateBackendProbe = true,
                            )
                        }
                        val outcomes = benchmark.runBackendInputLengthBenchmark(backend)
                        val successes = outcomes.count { "; result=success;" in it }
                        summaries += "${backend.name}: $successes/${outcomes.size} cases"
                    } catch (error: Exception) {
                        val detail = "${error::class.java.simpleName}: ${error.message ?: "backend unavailable"}".take(180)
                        summaries += "${backend.name}: experiment failed ($detail)"
                        diagnostics.warn("embedding.benchmark.backend.unavailable", "backend=${backend.name}; $detail")
                    } finally {
                        benchmark?.close()
                    }
                }
                operationMessage = "Qualcomm 同模型 GPU/CPU 实验结束：${summaries.joinToString("；")}。各档预热 1 次、测量 5 次，p50/p90 已写入诊断日志。实验失败不影响 NPU/GPU 生产路径。"
                diagnostics.info("embedding.benchmark.summary", summaries.joinToString("; "))
            } catch (cancelled: CancellationException) {
                diagnostics.warn("embedding.benchmark.cancelled")
                throw cancelled
            } catch (error: Exception) {
                operationMessage = "GPU/CPU 对照实验无法启动：${error::class.java.simpleName}: ${error.message ?: "unknown error"}"
                diagnostics.error("embedding.benchmark.failed", operationMessage)
            } finally {
                operationBusy = false
            }
        }
    }

    private fun runEmbeddingSelfTest(allowDiagnosticNpuProbe: Boolean = false) {
        if (operationBusy) return
        if (allowDiagnosticNpuProbe && !qualcommModelSelected) {
            operationMessage = "NPU 诊断探测只适用于已选择并通过 SHA-256 校验的 Qualcomm SM8650 专用模型。"
            diagnostics.warn("embedding.npu.diagnostic_probe.rejected", "reason=qualcomm_model_profile_not_selected")
            return
        }
        if (allowDiagnosticNpuProbe) {
            diagnostics.warn("embedding.npu.diagnostic_probe.started", "modelProfile=QUALCOMM_SM8650; purpose=one_shot_runtime_diagnosis; evidencePolicy=runtime_generated_only")
        }
        if (selectedEmbeddingDimensions > com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy.NATIVE_DIMENSIONS) {
            operationMessage = "当前 EmbeddingGemma 2 原生最高支持 768 维；已保留 ${selectedEmbeddingDimensions} 维选项，但需要切换到原生支持该维度的模型后才能测试。未启动不兼容的推理任务。"
            diagnostics.warn("embedding.selftest.unsupported_dimension_for_model", "model=EmbeddingGemma2; nativeDimensions=768; selectedDimensions=$selectedEmbeddingDimensions")
            return
        }
        val selfTestText = "Mistbell Tavern local embedding diagnostic test"
        if (!allowDiagnosticNpuProbe) {
            val profileId = modelAssets.cacheProfileId()
            val cacheLookupStarted = System.nanoTime()
            val cached = EmbeddingGemma2RuntimeAdapter.cachedQuery(
                query = selfTestText,
                outputDimensions = selectedEmbeddingDimensions,
                modelProfileId = profileId,
                onDiagnostic = { event, detail -> diagnostics.info(event, detail) },
            )
            val cacheLookupMillis = (System.nanoTime() - cacheLookupStarted) / 1_000_000L
            if (cached != null) {
                inferenceStatus = cached.status
                operationMessage = "本地向量自测缓存命中：${cached.vectors.single().size} 维；跳过模型校验、引擎初始化与推理。"
                diagnostics.info("embedding.selftest.succeeded", "cacheHit=true; modelProfileMatched=true; dimensions=${cached.vectors.single().size}; backendEvidence=${cached.status.backendEvidence}; cacheLookupMs=$cacheLookupMillis; engineInitializationSkipped=true")
                return
            }
        }
        operationBusy = true
        operationMessage = "正在准备本地 EmbeddingGemma 2 推理……"
        lifecycleScope.launch {
            var runtime: LiteRtLmEmbeddingGemma2Engine? = null
            var verifiedAsset: DownloadedEmbeddingModel? = null
            try {
                val asset = withContext(Dispatchers.IO) { modelAssets.verifyInstalled() }
                    ?: error("模型不存在或 SHA-256 校验失败；请重新下载模型。")
                verifiedAsset = asset
                val initializedRuntime = if (allowDiagnosticNpuProbe) {
                    withContext(Dispatchers.IO) {
                        LiteRtLmEmbeddingGemma2Engine(
                            context = applicationContext,
                            modelFile = asset.file,
                            npuModelCompatible = asset.isQualcommSm8650Optimized,
                            allowDiagnosticNpuProbe = true,
                            diagnostics = diagnostics,
                        )
                    }
                } else {
                    EmbeddingEngineProcessHolder.acquire(
                        context = applicationContext,
                        modelFile = asset.file,
                        npuModelCompatible = asset.isQualcommSm8650Optimized,
                        diagnostics = diagnostics,
                    )
                }
                runtime = initializedRuntime
                inferenceStatus = InferenceRuntimeStatus(
                    phase = InferencePhase.LOADING_MODEL,
                    modelId = com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2TextPolicy.MODEL_ID,
                    backend = InferenceBackend.UNKNOWN,
                    runtimeId = initializedRuntime.runtimeId,
                )
                if (allowDiagnosticNpuProbe) {
                    operationMessage = "正在运行 Qualcomm NPU 输入长度对照矩阵（7 次独立初始化/推理）……"
                    val outcomes = withContext(Dispatchers.IO) { initializedRuntime.runNpuInputLengthProbe() }
                    val successes = outcomes.count { "; result=success;" in it }
                    inferenceStatus = initializedRuntime.currentStatus()
                    val evidence = initializedRuntime.lastNpuEvidenceResult
                    val evidenceStatus = evidence?.status ?: "not_generated"
                    val evidenceName = evidence?.file?.name ?: "none"
                    operationMessage = "NPU 输入长度矩阵结束：成功 $successes/${outcomes.size}，失败 ${outcomes.size - successes}；证据状态=$evidenceStatus；文件=$evidenceName。可导出证据 JSON。"
                    diagnostics.info(
                        "embedding.npu.diagnostic_probe.matrix_summary",
                        "cases=${outcomes.size}; successes=$successes; failures=${outcomes.size - successes}; status=$evidenceStatus; evidenceFile=$evidenceName; apkSha256MatchesCurrent=${evidence?.apkSha256MatchesCurrent}",
                    )
                } else {
                    inferenceStatus = initializedRuntime.currentStatus()
                    operationMessage = "模型已初始化，正在执行真实文本向量测试……"
                    inferenceStatus = initializedRuntime.currentStatus().copy(phase = InferencePhase.RUNNING)
                    val result = EmbeddingGemma2RuntimeAdapter(
                        initializedRuntime,
                        outputDimensions = selectedEmbeddingDimensions,
                        onDiagnostic = { event, detail -> diagnostics.info(event, detail) },
                    ).embedQueries(listOf(selfTestText))
                    inferenceStatus = result.status
                    operationMessage =
                        "真实本地推理成功：${result.vectors.single().size} 维向量；配置后端 ${result.status.backend}。硬件实际调用仍需结合设备日志确认。"
                    diagnostics.info(
                        "embedding.selftest.succeeded",
                        "dimensions=${result.vectors.single().size}; configuredBackend=${result.status.backend}; evidence=${result.status.backendEvidence}; elapsedMs=${result.status.lastExecutionMillis}",
                    )
                }
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
                    !allowDiagnosticNpuProbe && verifiedAsset?.isQualcommSm8650Optimized == true &&
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
                    operationMessage = if (allowDiagnosticNpuProbe) {
                        "Qualcomm NPU 诊断探测失败：$detail。运行库仍保持未验证状态；请导出诊断日志检查 native/QAIRT 错误。"
                    } else "本地推理测试失败：$detail"
                    if (allowDiagnosticNpuProbe) {
                        diagnostics.error("embedding.npu.diagnostic_probe.inference_failed", "evidenceStatus=not_generated; $detail")
                    }
                    diagnostics.error("embedding.selftest.failed", detail)
                }
            } finally {
                if (allowDiagnosticNpuProbe) runCatching { runtime?.close() }
                operationBusy = false
            }
        }
    }

    override fun onDestroy() {
        // Process-scoped embedding engines intentionally survive Activity destruction.
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod", "FunctionNaming")
private fun DevelopmentHome(
    inferenceStatus: InferenceRuntimeStatus,
    operationMessage: String,
    operationBusy: Boolean,
    onOpenChat: () -> Unit,
    onOpenChatSettings: () -> Unit,
    onOpenImageGeneration: () -> Unit,
    onOpenCharacterLibrary: () -> Unit,
    onOpenWorldBookLibrary: () -> Unit,
    onOpenLicenses: () -> Unit,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    qualcommModelSelected: Boolean,
    onSelectModelProfile: (Boolean) -> Unit,
    selectedEmbeddingDimensions: Int,
    onCycleEmbeddingDimensions: () -> Unit,
    onRunEmbeddingTest: () -> Unit,
    onRunNpuDiagnosticProbe: () -> Unit,
    onRunAlternateBackendBenchmark: () -> Unit,
    onExportNpuEvidence: () -> Unit,
    onExportLogs: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                actions = {
                    TextButton(onClick = onOpenLicenses) { Text("许可") }
                },
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
                Text("复用 2.9 成熟交互，重接 3.0 数据层", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "沿用 2.9 已验证的界面交互模式，逐步接入 3.0 独立存储与业务逻辑。状态只反映已实现与已验证的内容，不把计划或依赖安装视为功能完成。",
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
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenChatSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("聊天 API 与生成设置")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("图片生成与本地图片库", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("使用独立图片 API Key 生成图片，并将内联图片安全保存到应用私有目录；远程图片只有在你明确点击下载后才会获取。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onOpenImageGeneration, modifier = Modifier.fillMaxWidth()) {
                            Text("进入图片生成与导出")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("角色卡管理", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("从本机角色库新建、编辑、删除角色卡；支持导入和导出 SillyTavern JSON。PNG/CHARX 容器导入及聊天会话绑定将在后续接线。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onOpenCharacterLibrary, modifier = Modifier.fillMaxWidth()) {
                            Text("进入角色管理")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("世界书管理", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("复用 2.9 的世界书列表和条目管理交互，支持本地保存、启停、编辑及 World Info JSON 导入导出。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onOpenWorldBookLibrary, modifier = Modifier.fillMaxWidth()) {
                            Text("进入世界书管理")
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("本地模型与实测", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("运行副本：软件内部私有目录。联网下载会另备份到 Download/向量模型，重装后可从文件导入；本地导入不会另建公共副本。", style = MaterialTheme.typography.bodyMedium)
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
                            "专用版针对骁龙 8 Gen 3/SM8650；NPU 是否可用仍取决于 APK 中的运行库与真机验证。联网下载会保留内部运行副本和公共目录备份；从文件导入只复制到内部目录。",
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
                        if (qualcommModelSelected) {
                            Spacer(Modifier.height(8.dp))
                            Text("仅供开发诊断：将尝试初始化 Qualcomm NPU，不会把运行库标记为已验证，也不会自动启用聊天生产路径。若 SDK/ABI 不兼容，可能失败；失败日志可用于定位。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = onRunNpuDiagnosticProbe, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                                Text("运行 NPU 输入长度矩阵（7 档并生成证据）")
                            }
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = onRunAlternateBackendBenchmark, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                                Text("实验：同一 Qualcomm 模型 GPU / CPU 基准")
                            }
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
                        Button(onClick = onExportLogs, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("导出诊断日志")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onExportNpuEvidence, enabled = !operationBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("导出最新 NPU 证据 JSON")
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
                "NPU 运行时已验证（7/7、运行时加载与推理成功；未独立证明 DSP 执行）"
                    .takeIf { status.backend == InferenceBackend.NPU && status.backendEvidence == BackendEvidence.RUNTIME_REPORTED },
                status.modelId?.let { "模型：$it" },
                status.acceleratorName?.let { "配置后端：$it" },
                status.backendEvidence.takeIf { it != BackendEvidence.NOT_AVAILABLE }?.let { "硬件证据：${when (it) { BackendEvidence.NOT_AVAILABLE -> "无"; BackendEvidence.CONFIGURED_ONLY -> "仅配置成功，未独立确认实际硬件调用"; BackendEvidence.RUNTIME_REPORTED -> "NPU 运行时已验证（7/7、运行时加载与推理成功；未独立证明 DSP 执行）"; BackendEvidence.DEVICE_VERIFIED -> "设备实测确认（需独立硬件执行证据）" }}" },
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
