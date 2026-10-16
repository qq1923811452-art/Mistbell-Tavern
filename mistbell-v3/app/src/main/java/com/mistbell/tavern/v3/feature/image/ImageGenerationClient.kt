package com.mistbell.tavern.v3.feature.image

import com.mistbell.tavern.v3.core.network.EndpointPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class ImageGenerationRequest(
    val prompt: String,
    val model: String,
    val count: Int = 1,
    val size: String = "1024x1024",
) {
    init {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }
        require(prompt.length <= MAX_PROMPT_CHARS) { "Image prompt exceeds the configured limit." }
        require(model.isNotBlank()) { "Image model ID must not be blank." }
        require(count in 1..MAX_IMAGES_PER_REQUEST) { "Image count must be between 1 and 4." }
        require(size in SUPPORTED_SIZES) { "Image size is not supported by this client." }
    }

    companion object {
        const val MAX_PROMPT_CHARS = 20_000
        const val MAX_IMAGES_PER_REQUEST = 4
        val SUPPORTED_SIZES = setOf("1024x1024", "1024x1536", "1536x1024", "auto")
    }
}

data class GeneratedImage(
    val base64Data: String? = null,
    val remoteUrl: String? = null,
    val revisedPrompt: String? = null,
) {
    init {
        require((!base64Data.isNullOrBlank()) xor (!remoteUrl.isNullOrBlank())) {
            "An image must contain exactly one of base64 data or a remote URL."
        }
    }
}

data class ImageGenerationResult(
    val model: String?,
    val images: List<GeneratedImage>,
) {
    init {
        require(images.isNotEmpty()) { "Image API returned no images." }
    }
}

class ImageGenerationException(
    val httpStatus: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Dedicated OpenAI-compatible image endpoint. The caller supplies the image-specific credential;
 * chat credentials are never read here. This blocking method must be called off the main thread.
 * Remote image URLs are returned but never fetched automatically by this client.
 */
class ImageGenerationClient(
    baseUrl: String,
    private val apiKeyProvider: () -> String,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val endpoint: String
    private val client: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(IMAGE_CALL_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .build()

    init {
        val validation = EndpointPolicy.validateBaseUrl(baseUrl)
        require(validation.isAllowed) { validation.reason ?: "Image API endpoint is not allowed." }
        endpoint = validation.normalizedBaseUrl + "/images/generations"
    }

    @Throws(ImageGenerationException::class)
    fun generate(request: ImageGenerationRequest): ImageGenerationResult {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) throw ImageGenerationException(message = "Image API credential is not configured.")

        val payload = JSONObject()
            .put("model", request.model)
            .put("prompt", request.prompt)
            .put("n", request.count)
            .put("size", request.size)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (error: IOException) {
            throw ImageGenerationException(message = "Image API request failed.", cause = error)
        }

        response.use { result ->
            if (result.code in REDIRECT_CODES) {
                throw ImageGenerationException(
                    httpStatus = result.code,
                    message = "Image API redirects are not followed for credential safety.",
                )
            }
            if (!result.isSuccessful) {
                throw ImageGenerationException(
                    httpStatus = result.code,
                    message = "Image API returned HTTP ${result.code}.",
                )
            }
            val body = result.body ?: throw ImageGenerationException(
                httpStatus = result.code,
                message = "Image API returned an empty response body.",
            )
            val text = try {
                readBounded(body)
            } catch (error: ImageGenerationException) {
                throw error
            } catch (error: IOException) {
                throw ImageGenerationException(message = "Image API response could not be read.", cause = error)
            }
            return parseResponse(text, request.count)
        }
    }

    private fun parseResponse(json: String, requestedCount: Int): ImageGenerationResult {
        val root = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw ImageGenerationException(message = "Image API returned invalid JSON.", cause = error)
        }
        val data = root.optJSONArray("data")
            ?: throw ImageGenerationException(message = "Image API response is missing the data array.")
        if (data.length() !in 1..requestedCount) {
            throw ImageGenerationException(message = "Image API returned an unexpected number of images.")
        }
        val images = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index)
                    ?: throw ImageGenerationException(message = "Image API returned an invalid image item.")
                val base64 = item.optString("b64_json", "").takeIf(String::isNotBlank)
                val rawUrl = item.optString("url", "").takeIf(String::isNotBlank)
                val safeUrl = rawUrl?.let(::validateRemoteImageUrl)
                if (base64 == null && safeUrl == null) {
                    throw ImageGenerationException(message = "Image API item has no usable image payload.")
                }
                if (base64 != null && safeUrl != null) {
                    throw ImageGenerationException(message = "Image API item contains conflicting image payloads.")
                }
                add(
                    GeneratedImage(
                        base64Data = base64,
                        remoteUrl = safeUrl,
                        revisedPrompt = item.optString("revised_prompt", "").takeIf(String::isNotBlank),
                    ),
                )
            }
        }
        return ImageGenerationResult(
            model = root.optString("model", "").takeIf(String::isNotBlank),
            images = images,
        )
    }

    private fun validateRemoteImageUrl(rawUrl: String): String {
        val uri = try {
            URI(rawUrl)
        } catch (error: Exception) {
            throw ImageGenerationException(message = "Image API returned an invalid image URL.", cause = error)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null
        ) {
            throw ImageGenerationException(message = "Image API returned a non-HTTPS or unsafe image URL.")
        }
        return uri.toASCIIString()
    }

    private fun readBounded(body: okhttp3.ResponseBody): String {
        val source = body.source()
        val buffer = Buffer()
        var totalBytes = 0L
        while (totalBytes <= MAX_RESPONSE_BYTES) {
            val remaining = MAX_RESPONSE_BYTES + 1L - totalBytes
            val count = source.read(buffer, minOf(8192L, remaining))
            if (count == -1L) break
            totalBytes += count
            if (totalBytes > MAX_RESPONSE_BYTES) {
                throw ImageGenerationException(message = "Image API response exceeds the configured size limit.")
            }
        }
        return buffer.readUtf8()
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        const val IMAGE_CALL_TIMEOUT_SECONDS = 180
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
