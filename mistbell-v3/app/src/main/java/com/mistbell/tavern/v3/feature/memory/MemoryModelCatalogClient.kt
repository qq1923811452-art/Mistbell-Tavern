package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.network.EndpointPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only model discovery for the memory settings screen.
 *
 * The response is bounded and only model IDs are retained; prompts, keys and response bodies are
 * never logged. Providers that do not expose an OpenAI-compatible `/models` endpoint surface a
 * normal error so the user can still type the model ID manually.
 */
class MemoryModelCatalogClient(
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val client =
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(MODEL_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    suspend fun fetch(
        baseUrl: String,
        apiKey: String?,
        onDiagnostic: (String, String) -> Unit = { _, _ -> },
    ): List<String> =
        suspendCancellableCoroutine { continuation ->
            val validation = EndpointPolicy.validateBaseUrl(baseUrl)
            require(validation.isAllowed) { validation.reason ?: "Base URL is invalid." }
            val normalized = requireNotNull(validation.normalizedBaseUrl)
            val uri = URI(normalized)
            val requestBuilder =
                Request.Builder()
                    .url("$normalized/models")
                    .get()
            apiKey?.trim()?.takeIf(String::isNotEmpty)?.let {
                requestBuilder.header("Authorization", "Bearer $it")
            }
            val call = client.newCall(requestBuilder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            runCatching {
                                response.use {
                                    check(it.isSuccessful) { "模型列表接口返回 HTTP ${it.code}。" }
                                    val body = it.body ?: error("模型列表响应为空。")
                                    parseModels(readBoundedBody(body))
                                }
                            }
                        if (!continuation.isActive) return
                        result.fold(
                            onSuccess = { models ->
                                runCatching {
                                    onDiagnostic(
                                        "memory.model_catalog.fetched",
                                        "count=${models.size}; host=${uri.host.orEmpty().lowercase(Locale.ROOT)}",
                                    )
                                }
                                continuation.resume(models)
                            },
                            onFailure = continuation::resumeWithException,
                        )
                    }
                },
            )
        }

    private fun readBoundedBody(body: ResponseBody): String {
        val buffer = Buffer()
        val source = body.source()
        while (buffer.size <= MAX_RESPONSE_BYTES) {
            val remaining = MAX_RESPONSE_BYTES + 1 - buffer.size
            if (source.read(buffer, minOf(READ_CHUNK_BYTES, remaining)) == -1L) break
        }
        require(buffer.size <= MAX_RESPONSE_BYTES) { "模型列表响应过大。" }
        return buffer.readUtf8().takeIf(String::isNotBlank) ?: error("模型列表响应为空。")
    }

    private fun parseModels(text: String): List<String> {
        val json = JSONObject(text)
        val array =
            json.optJSONArray("data") ?: json.optJSONArray("models")
                ?: error("响应缺少 data 或 models 数组。")
        val seen = mutableSetOf<String>()
        val models =
            buildList {
                for (index in 0 until array.length()) {
                    val id = array.optJSONObject(index)?.optString("id")?.trim().orEmpty()
                    if (id.isNotEmpty() && id.length <= MAX_MODEL_ID_CHARS && seen.add(id)) add(id)
                }
            }
        require(models.isNotEmpty()) { "接口没有返回可用模型。" }
        return models.take(MAX_MODELS)
    }

    companion object {
        private const val MODEL_LIST_TIMEOUT_SECONDS = 20L
        private const val READ_CHUNK_BYTES = 8_192L
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_MODEL_ID_CHARS = 200
        private const val MAX_MODELS = 200
    }
}
