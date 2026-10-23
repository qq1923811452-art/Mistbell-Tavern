package com.mistbell.tavern.v3.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Sends one minimal chat completion to verify endpoint, model and key together. */
class ChatConnectionTester(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    data class Result(val success: Boolean, val message: String)

    suspend fun test(baseUrl: String, modelId: String, apiKey: String): Result =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || modelId.isBlank()) {
                return@withContext Result(false, "请先填写地址和模型 ID。")
            }
            if (apiKey.isBlank()) {
                return@withContext Result(false, "请先填写 API Key。")
            }
            val started = System.nanoTime()
            runCatching {
                val endpoint = if (baseUrl.trimEnd('/').endsWith("/chat/completions")) {
                    baseUrl.trimEnd('/')
                } else {
                    baseUrl.trimEnd('/') + "/chat/completions"
                }
                val body = JSONObject()
                    .put("model", modelId)
                    .put("stream", false)
                    .put("max_tokens", 1)
                    .put(
                        "messages",
                        org.json.JSONArray().put(
                            JSONObject().put("role", "user").put("content", "ping"),
                        ),
                    )
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    if (response.isSuccessful) {
                        Result(true, "连接成功（${elapsed}ms）。")
                    } else {
                        Result(false, "连接失败：HTTP ${response.code}（${elapsed}ms）。")
                    }
                }
            }.getOrElse { error ->
                Result(false, "连接失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
}

private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val READ_TIMEOUT_SECONDS = 30L
