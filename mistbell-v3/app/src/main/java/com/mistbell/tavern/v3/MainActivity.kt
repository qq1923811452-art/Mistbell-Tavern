@file:Suppress("LargeClass", "MagicNumber")

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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
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
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2ModelAssetManager
import com.mistbell.tavern.v3.feature.embedding.DownloadedEmbeddingModel
import com.mistbell.tavern.v3.feature.embedding.EmbeddingGemma2RuntimeAdapter
import com.mistbell.tavern.v3.feature.embedding.LiteRtLmEmbeddingGemma2Engine
import com.mistbell.tavern.v3.feature.embedding.EmbeddingEngineProcessHolder
import com.mistbell.tavern.v3.feature.embedding.EmbeddingEngineProcessHolder.CloseReason
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.inference.InferenceBackend
import com.mistbell.tavern.v3.core.inference.InferencePhase
import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import com.mistbell.tavern.v3.feature.about.LicensesScreen
import com.mistbell.tavern.v3.feature.chat.ChatScreen
import com.mistbell.tavern.v3.feature.character.CharacterCardLibraryScreen
import com.mistbell.tavern.v3.feature.worldbook.WorldBookLibraryScreen
import com.mistbell.tavern.v3.feature.image.ImageGenerationScreen
import com.mistbell.tavern.v3.feature.settings.LocalModelScreen
import com.mistbell.tavern.v3.feature.settings.MemorySettingsScreen
import com.mistbell.tavern.v3.feature.settings.SettingsHomeScreen
import com.mistbell.tavern.v3.core.settings.getIntCompat
import com.mistbell.tavern.v3.ui.TavernBackground
import com.mistbell.tavern.v3.ui.TavernBottomBar
import com.mistbell.tavern.v3.ui.TavernTab


class MainActivity : ComponentActivity() {
    private lateinit var diagnostics: DiagnosticLogStore
    private lateinit var modelAssets: EmbeddingGemma2ModelAssetManager
    private var inferenceStatus by mutableStateOf(InferenceRuntimeStatus.idle())
    private var operationMessage by mutableStateOf("正在检查本地模型……")
    private var operationBusy by mutableStateOf(false)
    private var showImageGeneration by mutableStateOf(false)
    private var showLicenses by mutableStateOf(false)
    private var mainTab by mutableStateOf(TavernTab.CHAT)
    private var showLocalModel by mutableStateOf(false)
    private var showMemorySettings by mutableStateOf(false)
    private var contextTokens by mutableStateOf(32_000)
    private var memoryEnabled by mutableStateOf(true)
    private var maxOutputTokens by mutableStateOf(4096)
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
        contextTokens = settings.getIntCompat("chat_context_tokens", 32_000)
        memoryEnabled = settings.getBoolean("memory_extraction_enabled", true)
        maxOutputTokens = settings.getInt("chat_max_tokens", 4096).coerceIn(1, 200_000)
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
            BackHandler(
                enabled = showLicenses || showImageGeneration || showCharacterLibrary ||
                    showWorldBookLibrary || showLocalModel || showMemorySettings,
            ) {
                when {
                    showLicenses -> showLicenses = false
                    showImageGeneration -> showImageGeneration = false
                    showCharacterLibrary -> showCharacterLibrary = false
                    showWorldBookLibrary -> showWorldBookLibrary = false
                    showMemorySettings -> showMemorySettings = false
                    showLocalModel -> showLocalModel = false
                }
            }
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
                    } else if (showMemorySettings) {
                        MemorySettingsScreen(onBack = { showMemorySettings = false })
                    } else if (showLocalModel) {
                        LocalModelScreen(
                            inferenceStatus = inferenceStatus,
                            operationMessage = operationMessage,
                            operationBusy = operationBusy,
                            qualcommModelSelected = qualcommModelSelected,
                            selectedEmbeddingDimensions = selectedEmbeddingDimensions,
                            onSelectModelProfile = ::selectModelProfile,
                            onCycleEmbeddingDimensions = ::cycleEmbeddingDimensions,
                            onDownloadModel = ::downloadEmbeddingModel,
                            onImportModel = { importModel.launch(arrayOf("*/*")) },
                            onRunEmbeddingTest = { runEmbeddingSelfTest() },
                            onRunNpuDiagnosticProbe = { runEmbeddingSelfTest(allowDiagnosticNpuProbe = true) },
                            onRunAlternateBackendBenchmark = { runAlternateBackendBenchmark() },
                            onExportNpuEvidence = { exportNpuEvidence.launch("npu-evidence.json") },
                            onExportLogs = { exportDiagnostics.launch("mistbell-diagnostics.txt") },
                            onBack = { showLocalModel = false },
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                when (mainTab) {
                                    TavernTab.CHAT -> TavernBackground {
                                        ChatScreen(
                                            onBack = { mainTab = TavernTab.SETTINGS },
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
                                            contextTokens = contextTokens,
                                            memoryEnabled = memoryEnabled,
                                            maxOutputTokens = maxOutputTokens,
                                            onMaxOutputTokensChange = { tokens ->
                                                maxOutputTokens = tokens
                                                getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
                                                    .edit()
                                                    .putInt("chat_max_tokens", tokens)
                                                    .apply()
                                            },
                                            onContextTokensChange = { tokens ->
                                                getSharedPreferences("mistbell-v3-settings", MODE_PRIVATE)
                                                    .edit()
                                                    .putInt("chat_context_tokens", tokens)
                                                    .putInt(
                                                        "chat_history_max_chars",
                                                        (tokens * 2).coerceIn(4_000, 2_000_000),
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
                                            onOpenEmbedding = { showLocalModel = true },
                                            onOpenMemorySettings = { showMemorySettings = true },
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
