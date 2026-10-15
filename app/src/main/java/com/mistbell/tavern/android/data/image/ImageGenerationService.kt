package com.mistbell.tavern.android.data.image

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Dedicated image API configuration. Kept separate from chat/embedding provider credentials. */
data class ImageGenerationConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val width: Int = 1024,
    val height: Int = 1024,
)

data class GeneratedImage(
    val file: File,
    val revisedPrompt: String?,
)

/**
 * First-party image generation adapter for OpenAI-compatible /images/generations endpoints.
 *
 * The returned image is copied into app-private storage so the chat UI can render it without
 * depending on a short-lived provider URL. ComfyUI and other non-OpenAI protocols should be
 * implemented as separate adapters rather than pretending their request schemas are identical.
 */
class ImageGenerationService(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {
    @Suppress("LongMethod")
    suspend fun generate(
        config: ImageGenerationConfig,
        prompt: String,
    ): GeneratedImage =
        withContext(Dispatchers.IO) {
            require(config.baseUrl.isNotBlank()) { "请先配置生图 API 基础地址" }
            require(config.model.isNotBlank()) { "请先配置生图模型名称" }
            require(prompt.isNotBlank()) { "生图提示词不能为空" }
            require(config.width in MIN_DIMENSION..MAX_DIMENSION && config.height in MIN_DIMENSION..MAX_DIMENSION) {
                "图片尺寸必须在 256–2048 范围内"
            }

            val endpoint = endpoint(config.baseUrl)
            val payload =
                buildJsonObject {
                    put("model", config.model.trim())
                    put("prompt", prompt.trim())
                    put("n", 1)
                    put("size", "${config.width}x${config.height}")
                }
            val requestBuilder =
                Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
            if (config.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
            }

            val response =
                client.newCall(requestBuilder.build()).execute().use { httpResponse ->
                    val body = httpResponse.body?.string().orEmpty()
                    if (!httpResponse.isSuccessful) {
                        // Do not surface arbitrary provider error bodies: some gateways echo request data.
                        throw IOException("生图 API 返回 HTTP ${httpResponse.code}: ${httpResponse.message}")
                    }
                    try {
                        Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
                    } catch (e: SerializationException) {
                        throw IOException("生图 API 返回的不是有效 JSON", e)
                    }
                }

            val first = response["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IOException("生图 API 响应中没有 data[0]")
            val remoteUrl = first["url"]?.jsonPrimitive?.contentOrNull()
            val base64 = first["b64_json"]?.jsonPrimitive?.contentOrNull()
            val outputFile = createOutputFile()
            when {
                !base64.isNullOrBlank() -> {
                    val encoded = base64.substringAfter("base64,", base64)
                    val bytes =
                        try {
                            Base64.getDecoder().decode(encoded)
                        } catch (e: IllegalArgumentException) {
                            throw IOException("生图 API 返回了无效的 Base64 图片", e)
                        }
                    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                        throw IOException("生成图片为空或超过 30 MB 限制")
                    }
                    outputFile.writeBytes(bytes)
                }
                !remoteUrl.isNullOrBlank() -> downloadImage(remoteUrl, config.baseUrl, config.apiKey, outputFile)
                else -> throw IOException("生图 API 响应既没有 url，也没有 b64_json")
            }
            GeneratedImage(
                file = outputFile,
                revisedPrompt = first["revised_prompt"]?.jsonPrimitive?.contentOrNull(),
            )
        }

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "生图 API 地址必须以 http:// 或 https:// 开头"
        }
        return if (base.endsWith("/images/generations")) base else "$base/images/generations"
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount")
    private fun downloadImage(
        url: String,
        baseUrl: String,
        apiKey: String,
        outputFile: File,
    ) {
        val request =
            Request.Builder().url(url).get().apply {
                // Signed image URLs normally need no authorization; only add it for same-host URLs.
                val imageHost = runCatching { java.net.URL(url).host }.getOrNull()
                val apiHost = runCatching { java.net.URL(baseUrl).host }.getOrNull()
                if (apiKey.isNotBlank() && imageHost != null && imageHost == apiHost) {
                    header("Authorization", "Bearer ${apiKey.trim()}")
                }
            }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载生成图片失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("图片响应为空")
            if (body.contentLength() > MAX_IMAGE_BYTES) throw IOException("生成图片超过 30 MB 限制")
            body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) throw IOException("生成图片超过 30 MB 限制")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (outputFile.length() == 0L) throw IOException("下载到的图片为空")
        }
    }

    private fun createOutputFile(): File {
        val directory = File(context.filesDir, OUTPUT_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建生图输出目录")
        }
        return File.createTempFile("mistbell-image-", ".png", directory)
    }

    companion object {
        private const val MIN_DIMENSION = 256
        private const val MAX_DIMENSION = 2048
        private const val MAX_IMAGE_BYTES = 30L * 1024L * 1024L
        private const val OUTPUT_DIRECTORY = "generated-images"
        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val READ_TIMEOUT_SECONDS = 180L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 210L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? {
    return if (isString || toString() != "null") {
        content
    } else {
        null
    }
}
