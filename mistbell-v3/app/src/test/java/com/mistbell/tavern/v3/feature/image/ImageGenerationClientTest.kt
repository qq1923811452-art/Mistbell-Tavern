package com.mistbell.tavern.v3.feature.image

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationClientTest {
    @Test
    fun sendsImageSpecificCredentialAndParsesBase64Image() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"model":"image-model","data":[{"b64_json":"aGVsbG8=","revised_prompt":"A lighthouse"}]}"""),
            )
            server.start()
            val client = ImageGenerationClient(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKeyProvider = { "image-only-secret" },
            )

            val result = client.generate(
                ImageGenerationRequest(prompt = "A lighthouse", model = "image-model"),
            )
            val recorded = server.takeRequest()

            assertEquals("Bearer image-only-secret", recorded.getHeader("Authorization"))
            assertTrue(recorded.path!!.endsWith("/v1/images/generations"))
            assertTrue(recorded.body.readUtf8().contains("\"prompt\":\"A lighthouse\""))
            assertEquals("image-model", result.model)
            assertEquals("aGVsbG8=", result.images.single().base64Data)
            assertEquals("A lighthouse", result.images.single().revisedPrompt)
        }
    }

    @Test
    fun refusesRemoteHttpEndpointsBeforeMakingRequests() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ImageGenerationClient(
                baseUrl = "http://example.com/v1",
                apiKeyProvider = { "secret" },
            )
        }

        assertTrue(error.message!!.contains("HTTPS"))
    }

    @Test
    fun doesNotFollowRedirectsThatCouldLeakCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/collect"))
            server.start()
            val client = ImageGenerationClient(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKeyProvider = { "image-only-secret" },
                httpClient = OkHttpClient(),
            )

            val error = assertThrows(ImageGenerationException::class.java) {
                client.generate(ImageGenerationRequest(prompt = "A lighthouse", model = "image-model"))
            }

            assertEquals(302, error.httpStatus)
            assertTrue(error.message!!.contains("redirects are not followed"))
            assertFalse(server.takeRequest().getHeader("Authorization").isNullOrBlank())
        }
    }

    @Test
    fun rejectsRemoteImageUrlsThatAreNotHttps() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"data":[{"url":"http://example.com/image.png"}]}"""),
            )
            server.start()
            val client = ImageGenerationClient(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKeyProvider = { "image-only-secret" },
            )

            val error = assertThrows(ImageGenerationException::class.java) {
                client.generate(ImageGenerationRequest(prompt = "A lighthouse", model = "image-model"))
            }

            assertTrue(error.message!!.contains("non-HTTPS"))
        }
    }

    @Test
    fun rejectsFewerImagesThanRequested() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"b64_json":"aGVsbG8="}]}"""),
            )
            server.start()
            val client = ImageGenerationClient(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKeyProvider = { "image-only-secret" },
            )

            val error = assertThrows(ImageGenerationException::class.java) {
                client.generate(
                    ImageGenerationRequest(prompt = "Two lanterns", model = "image-model", count = 2),
                )
            }

            assertTrue(error.message!!.contains("unexpected number of images"))
        }
    }

    @Test
    fun requestRejectsBlankPromptsAndInvalidCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageGenerationRequest(prompt = "  ", model = "image-model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageGenerationRequest(prompt = "A lighthouse", model = "image-model", count = 5)
        }
    }
}
