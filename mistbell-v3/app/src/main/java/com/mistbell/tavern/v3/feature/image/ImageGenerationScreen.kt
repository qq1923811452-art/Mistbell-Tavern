package com.mistbell.tavern.v3.feature.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

private data class PendingRemoteImage(
    val url: String,
    val prompt: String,
    val model: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) {
        context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
    }
    val store = remember(context) {
        GeneratedImageStore(File(context.filesDir, "generated-images"))
    }
    val scope = rememberCoroutineScope()
    var baseUrl by remember(settings) {
        mutableStateOf(settings.getString("image_api_base_url", "https://api.odys.work/v1") ?: "https://api.odys.work/v1")
    }
    var model by remember(settings) {
        mutableStateOf(settings.getString("image_model_id", "") ?: "")
    }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("生图 API 使用独立密钥，不会读取聊天 API Key。密钥仅保存在当前页面内存中。") }
    var savedImages by remember { mutableStateOf(store.list()) }
    var pendingRemoteImages by remember { mutableStateOf(emptyList<PendingRemoteImage>()) }
    var previewImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var exportTarget by remember { mutableStateOf<StoredGeneratedImage?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) {
            if (uri == null) status = "已取消图片导出。"
        } else {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("导出目标无法打开。")
                        output.use { store.export(target.id, it) }
                    }
                }.onSuccess {
                    status = "图片已导出：${target.fileName}"
                }.onFailure {
                    status = "图片导出失败：${it.message ?: it::class.java.simpleName}"
                }
            }
        }
    }

    fun refreshLibrary(previewId: String? = null) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val images = store.list()
                val selected = images.firstOrNull { it.id == previewId } ?: images.firstOrNull()
                val bitmap = selected?.let { BitmapFactory.decodeFile(File(context.filesDir, "generated-images/${it.fileName}").absolutePath)?.asImageBitmap() }
                images to bitmap
            }
            savedImages = result.first
            previewImage = result.second
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mistbell Tavern · 图片生成") },
                navigationIcon = { Button(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("独立生图 API", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = {
                                baseUrl = it
                                settings.edit().putString("image_api_base_url", it).apply()
                            },
                            label = { Text("图片 API Base URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = {
                                model = it
                                settings.edit().putString("image_model_id", it).apply()
                            },
                            label = { Text("图片模型 ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("独立图片 API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it.take(ImageGenerationRequest.MAX_PROMPT_CHARS) },
                            label = { Text("图片描述") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                        )
                        Button(
                            onClick = {
                                if (busy) return@Button
                                if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank() || prompt.isBlank()) {
                                    status = "请填写图片 API 地址、模型 ID、独立密钥和图片描述。"
                                    return@Button
                                }
                                val keyForRequest = apiKey
                                val promptForRequest = prompt.trim()
                                val modelForRequest = model.trim()
                                val baseForRequest = baseUrl.trim()
                                busy = true
                                status = "正在请求图片生成……"
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val client = ImageGenerationClient(baseForRequest, { keyForRequest })
                                            val result = client.generate(
                                                ImageGenerationRequest(prompt = promptForRequest, model = modelForRequest),
                                            )
                                            val stored = mutableListOf<StoredGeneratedImage>()
                                            val remote = mutableListOf<PendingRemoteImage>()
                                            result.images.forEach { image ->
                                                if (image.base64Data != null) {
                                                    stored += store.save(image, promptForRequest, result.model ?: modelForRequest)
                                                } else if (image.remoteUrl != null) {
                                                    remote += PendingRemoteImage(image.remoteUrl, promptForRequest, result.model ?: modelForRequest)
                                                }
                                            }
                                            Triple(stored, remote, store.list())
                                        }
                                    }.onSuccess { result ->
                                        savedImages = result.third
                                        pendingRemoteImages = result.second
                                        refreshLibrary(result.first.firstOrNull()?.id)
                                        status = when {
                                            result.first.isNotEmpty() && result.second.isEmpty() ->
                                                "生成完成，已安全保存 ${result.first.size} 张图片。"
                                            result.second.isNotEmpty() ->
                                                "服务端返回了远程图片地址。为避免未经允许的网络访问，请点击对应按钮后再下载保存。"
                                            else -> "图片 API 未返回可保存的图片。"
                                        }
                                    }.onFailure { error ->
                                        status = "图片生成失败：${error.message ?: error::class.java.simpleName}"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "生成中……" else "生成图片并保存") }
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (previewImage != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("最近生成的图片", style = MaterialTheme.typography.titleMedium)
                            Image(
                                bitmap = previewImage!!,
                                contentDescription = "最近生成的图片",
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                            )
                        }
                    }
                }
            }
            if (pendingRemoteImages.isNotEmpty()) {
                item {
                    Text("远程图片（需要你明确点击下载）", style = MaterialTheme.typography.titleMedium)
                }
                items(pendingRemoteImages) { remote ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(remote.url, style = MaterialTheme.typography.bodySmall)
                            Button(
                                enabled = !busy,
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    status = "正在下载你选择的远程图片……"
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                val bytes = downloadExplicitlySelectedImage(remote.url)
                                                val encoded = Base64.getEncoder().encodeToString(bytes)
                                                store.save(GeneratedImage(base64Data = encoded), remote.prompt, remote.model)
                                            }
                                        }.onSuccess { image ->
                                            pendingRemoteImages = pendingRemoteImages - remote
                                            refreshLibrary(image.id)
                                            status = "远程图片已下载并保存：${image.fileName}"
                                        }.onFailure { error ->
                                            status = "远程图片下载失败：${error.message ?: error::class.java.simpleName}"
                                        }
                                        busy = false
                                    }
                                },
                            ) { Text("下载并保存这张图片") }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地图片库（${savedImages.size} 张）", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { refreshLibrary() }, enabled = !busy) { Text("刷新") }
                }
            }
            items(savedImages, key = StoredGeneratedImage::id) { image ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(image.fileName, style = MaterialTheme.typography.bodyMedium)
                        Text("${image.mimeType} · ${image.sizeBytes / 1024} KiB · ${image.prompt.take(120)}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    exportTarget = image
                                    exportLauncher.launch(image.fileName)
                                },
                            ) { Text("导出") }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        val deleted = withContext(Dispatchers.IO) { store.delete(image.id) }
                                        refreshLibrary()
                                        status = if (deleted) "已删除 ${image.fileName}" else "图片删除失败。"
                                    }
                                },
                            ) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

private fun downloadExplicitlySelectedImage(url: String): ByteArray {
    val uri = try {
        URI(url)
    } catch (error: Exception) {
        throw IOException("图片 URL 无效。", error)
    }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
        "只允许下载安全的 HTTPS 图片地址。"
    }
    val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder().url(uri.toASCIIString()).get().build()
    client.newCall(request).execute().use { response ->
        if (response.code in 300..399) throw IOException("图片地址发生重定向，已拒绝下载。")
        if (!response.isSuccessful) throw IOException("图片服务器返回 HTTP ${response.code}。")
        val body = response.body ?: throw IOException("图片服务器返回空响应。")
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (total <= GeneratedImageStore.MAX_IMAGE_BYTES) {
            val count = source.read(
                buffer,
                minOf(8192L, GeneratedImageStore.MAX_IMAGE_BYTES + 1L - total),
            )
            if (count == -1L) break
            total += count
            if (total > GeneratedImageStore.MAX_IMAGE_BYTES) {
                throw IOException("远程图片超过保存大小限制。")
            }
        }
        return buffer.readByteArray()
    }
}
