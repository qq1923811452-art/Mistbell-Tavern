package com.mistbell.tavern.android.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Gemini 密钥无效状态码（detekt 数字需提常量）
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403

/**
 * 接口类型：决定 baseUrl 规范化、模型列表端点与连接测试的协议形态。
 * custom = 任意 OpenAI 兼容网关（与 openai 同协议，不预设地址）。
 */
object ApiType {
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"
    const val CUSTOM = "custom"
}

/**
 * 规范化 baseUrl：去掉末尾斜杠与已包含的 /chat/completions 路径段。
 * 纯函数，便于单测。
 */
internal fun normalizeApiBaseUrl(apiUrl: String): String {
    var base = apiUrl.trim()
    if (base.endsWith("/chat/completions")) {
        base = base.removeSuffix("/chat/completions")
    }
    while (base.endsWith("/")) {
        base = base.dropLast(1)
    }
    return base
}

/**
 * 按接口类型拼模型列表端点。纯函数，便于单测。
 * - openai/custom：GET {base}/models（Bearer）
 * - anthropic：GET {base}/v1/models（x-api-key）
 * - gemini：GET {base}/v1beta/models（x-goog-api-key header）
 */
internal fun resolveModelsUrl(
    apiUrl: String,
    type: String,
): String {
    val base = normalizeApiBaseUrl(apiUrl)
    return when (type) {
        ApiType.ANTHROPIC -> "$base/v1/models"
        ApiType.GEMINI -> "$base/v1beta/models"
        else -> "$base/models"
    }
}

/**
 * 各接口类型的预设 baseUrl（编辑对话框选类型时自动填充）
 */
fun defaultBaseUrlForType(type: String): String =
    when (type) {
        ApiType.OPENAI -> "https://api.openai.com/v1"
        ApiType.ANTHROPIC -> "https://api.anthropic.com"
        ApiType.GEMINI -> "https://generativelanguage.googleapis.com"
        else -> ""
    }

/**
 * API 连接测试 + 模型列表拉取服务（OkHttp 直连，不经 LlmClient）
 */
class ApiTestService {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

    /**
     * 测试 API 连接（按接口类型走对应协议的最小请求）
     */
    suspend fun testConnection(
        apiUrl: String,
        apiKey: String,
        model: String = "gpt-3.5-turbo",
        type: String = ApiType.OPENAI,
    ): ApiTestResult =
        withContext(Dispatchers.IO) {
            try {
                if (apiUrl.isBlank()) {
                    return@withContext ApiTestResult.Error("API 地址不能为空")
                }
                if (apiKey.isBlank()) {
                    return@withContext ApiTestResult.Error("API 密钥不能为空")
                }
                when (type) {
                    ApiType.ANTHROPIC -> testAnthropic(apiUrl, apiKey, model)
                    ApiType.GEMINI -> testGemini(apiUrl, apiKey, model)
                    else -> testOpenAiCompatible(apiUrl, apiKey, model)
                }
            } catch (e: java.net.UnknownHostException) {
                ApiTestResult.Error("无法连接到服务器，请检查网络或 API 地址")
            } catch (e: java.net.SocketTimeoutException) {
                ApiTestResult.Error("连接超时，请检查网络")
            } catch (e: javax.net.ssl.SSLException) {
                ApiTestResult.Error("SSL 证书验证失败")
            } catch (e: Exception) {
                ApiTestResult.Error("测试失败: ${e.message ?: "未知错误"}")
            }
        }

    /** 拉取模型列表（按接口类型适配端点与认证） */
    suspend fun fetchModels(
        apiUrl: String,
        apiKey: String,
        type: String = ApiType.OPENAI,
    ): FetchModelsResult =
        withContext(Dispatchers.IO) {
            try {
                if (apiUrl.isBlank()) {
                    return@withContext FetchModelsResult.Error("API 地址不能为空")
                }
                if (apiKey.isBlank()) {
                    return@withContext FetchModelsResult.Error("API 密钥不能为空")
                }

                val url = resolveModelsUrl(apiUrl, type)
                val builder =
                    Request.Builder()
                        .url(url)
                when (type) {
                    ApiType.ANTHROPIC -> {
                        builder.addHeader("x-api-key", apiKey)
                        builder.addHeader("anthropic-version", "2023-06-01")
                    }
                    ApiType.GEMINI -> builder.addHeader("x-goog-api-key", apiKey)
                    else -> builder.addHeader("Authorization", "Bearer $apiKey")
                }

                val response = client.newCall(builder.get().build()).execute()
                response.use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        return@withContext FetchModelsResult.Error("获取模型失败 (${response.code})")
                    }

                    val models = parseModelsJson(body, type)
                    FetchModelsResult.Success(models)
                }
            } catch (e: java.net.UnknownHostException) {
                FetchModelsResult.Error("无法连接到服务器，请检查网络或 API 地址")
            } catch (e: java.net.SocketTimeoutException) {
                FetchModelsResult.Error("连接超时，请检查网络")
            } catch (e: Exception) {
                FetchModelsResult.Error("获取模型失败: ${e.message ?: "未知错误"}")
            }
        }

    // ---- OpenAI 兼容（openai / custom）----

    private fun testOpenAiCompatible(
        apiUrl: String,
        apiKey: String,
        model: String,
    ): ApiTestResult {
        val base = normalizeApiBaseUrl(apiUrl)
        val testUrl =
            when {
                base.endsWith("/v1") -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }

        val requestBody =
            JSONObject().apply {
                put("model", model)
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", "Hello")
                            },
                        )
                    },
                )
                put("max_tokens", 5)
                put("stream", false)
            }.toString()

        val request =
            Request.Builder()
                .url(testUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

        return client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                    if (body != null && runCatching { JSONObject(body).has("choices") }.getOrDefault(false)) {
                        ApiTestResult.Success("连接成功")
                    } else {
                        ApiTestResult.Error("响应格式异常")
                    }
                }
                response.code == 401 -> ApiTestResult.Error("API 密钥无效 (401)")
                response.code == 429 -> ApiTestResult.Error("请求过于频繁 (429)")
                response.code == 404 -> ApiTestResult.Error("API 端点未找到 (404)")
                else -> ApiTestResult.Error("请求失败 (${response.code})")
            }
        }
    }

    // ---- Anthropic：POST /v1/messages（x-api-key + anthropic-version）----

    private fun testAnthropic(
        apiUrl: String,
        apiKey: String,
        model: String,
    ): ApiTestResult {
        val base = normalizeApiBaseUrl(apiUrl)
        val testUrl = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        val effectiveModel = model.ifBlank { "claude-sonnet-4-20250514" }

        val requestBody =
            JSONObject().apply {
                put("model", effectiveModel)
                put("max_tokens", 1)
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", "Hi")
                            },
                        )
                    },
                )
            }.toString()

        val request =
            Request.Builder()
                .url(testUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

        return client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> ApiTestResult.Success("连接成功")
                response.code == 401 -> ApiTestResult.Error("API 密钥无效 (401)")
                else -> ApiTestResult.Error("请求失败 (${response.code})")
            }
        }
    }

    // ---- Gemini：POST /v1beta/models/{model}:generateContent（x-goog-api-key header）----

    private fun testGemini(
        apiUrl: String,
        apiKey: String,
        model: String,
    ): ApiTestResult {
        val base = normalizeApiBaseUrl(apiUrl)
        val effectiveModel = model.ifBlank { "gemini-2.0-flash" }
        val testUrl =
            if (base.endsWith("/v1beta")) {
                "$base/models/$effectiveModel:generateContent"
            } else {
                "$base/v1beta/models/$effectiveModel:generateContent"
            }

        val requestBody =
            JSONObject().apply {
                put(
                    "contents",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put(
                                    "parts",
                                    JSONArray().apply {
                                        put(JSONObject().apply { put("text", "Hi") })
                                    },
                                )
                            },
                        )
                    },
                )
            }.toString()

        val request =
            Request.Builder()
                .url(testUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-goog-api-key", apiKey)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

        return client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> ApiTestResult.Success("连接成功")
                response.code == HTTP_BAD_REQUEST || response.code == HTTP_FORBIDDEN ->
                    ApiTestResult.Error("API 密钥无效 (${response.code})")
                else -> ApiTestResult.Error("请求失败 (${response.code})")
            }
        }
    }

    companion object {
        /** 解析各接口类型的 /models 响应为模型 id 列表（容错：结构不符返回空） */
        internal fun parseModelsJson(
            body: String,
            type: String,
        ): List<String> =
            runCatching {
                // 用 kotlinx.serialization 而非 org.json：JVM 单测环境无 Android org.json 实现
                when (type) {
                    ApiType.GEMINI -> {
                        // Gemini: {"models": [{"name": "models/gemini-2.0-flash", ...}]}
                        val json = Json.parseToJsonElement(body).jsonObject
                        json["models"]?.jsonArray
                            ?.mapNotNull {
                                it.jsonObject["name"]?.jsonPrimitive?.content
                                    ?.removePrefix("models/")
                                    ?.takeIf(String::isNotBlank)
                            }
                            ?: emptyList()
                    }
                    else -> {
                        // OpenAI / Anthropic: {"data": [{"id": "gpt-4o", ...}]}；部分兼容网关直接返回数组
                        val element = Json.parseToJsonElement(body)
                        val arr =
                            when (element) {
                                is JsonObject -> element["data"]?.jsonArray
                                is JsonArray -> element
                                else -> null
                            }
                        arr
                            ?.mapNotNull {
                                when (it) {
                                    is JsonObject ->
                                        it["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                                    is JsonPrimitive -> it.content.takeIf(String::isNotBlank)
                                    else -> null
                                }
                            }
                            ?: emptyList()
                    }
                }
            }.getOrDefault(emptyList())
    }
}

/**
 * API 测试结果
 */
sealed class ApiTestResult {
    data class Success(val message: String) : ApiTestResult()

    data class Error(val message: String) : ApiTestResult()
}

/**
 * 模型列表拉取结果
 */
sealed class FetchModelsResult {
    data class Success(val models: List<String>) : FetchModelsResult()

    data class Error(val message: String) : FetchModelsResult()
}
