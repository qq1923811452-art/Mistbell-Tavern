package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenAiCompatibleChatClientTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamsContentUsingOnlyTheSelectedChatProvider() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":17,\"completion_tokens\":2,\"total_tokens\":19,\"prompt_tokens_details\":{\"cached_tokens\":9}}}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()
        val observer = RecordingObserver()
        val client = clientFor(server, apiKey = "test-chat-key")

        client.stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(temperature = 0.25, maxTokens = 1234),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertNull(observer.failure)
        assertEquals("Hello there", observer.output.toString())
        assertEquals(ChatTokenUsage(17, 2, 19, 9), observer.usage)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/v1/chat/completions", request!!.path)
        assertEquals("Bearer test-chat-key", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("chat-model", payload.getString("model"))
        assertTrue(payload.getBoolean("stream"))
        assertEquals(0.25, payload.getDouble("temperature"), 0.0001)
        assertEquals(1234, payload.getInt("max_tokens"))
        assertTrue(payload.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals("Hi", payload.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun remoteProviderWithoutCredentialIsRejectedBeforeNetworkRequest() {
        server.start()
        val profile = ProviderProfile(
            id = "chat",
            kind = ProviderKind.CHAT,
            baseUrl = server.url("/v1").toString().trimEnd('/').replace("http://", "https://"),
            modelId = "chat-model",
        )
        val client = OpenAiCompatibleChatClient(
            providers = ProviderProfileSet(listOf(profile), mapOf(ProviderKind.CHAT to profile.id)),
            credentials = CredentialResolver { null },
            client = OkHttpClient(),
        )

        try {
            client.stream(listOf(PromptMessage(PromptRole.USER, "Hi")), RecordingObserver())
            throw AssertionError("Expected missing remote credential to be rejected.")
        } catch (_: ChatClientConfigurationException) {
            // Expected: no implicit provider fallback and no unauthenticated remote request.
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirectsAreNotFollowedAndBearerKeyIsNotForwarded() {
        val secondServer = MockWebServer()
        try {
            secondServer.start()
            secondServer.enqueue(MockResponse().setResponseCode(200).setBody("unexpected"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", secondServer.url("/steal").toString()),
            )
            server.start()
            val observer = RecordingObserver()
            clientFor(server, apiKey = "test-chat-key")
                .stream(listOf(PromptMessage(PromptRole.USER, "Hi")), observer)

            assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
            assertEquals(ChatFailureKind.HTTP_STATUS, observer.failure?.kind)
            assertEquals(302, observer.failure?.httpStatus)
            assertEquals(0, secondServer.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("Bearer test-chat-key", request?.getHeader("Authorization"))
        } finally {
            secondServer.shutdown()
        }
    }

    private fun clientFor(server: MockWebServer, apiKey: String): OpenAiCompatibleChatClient {
        val profile = ProviderProfile(
            id = "chat",
            kind = ProviderKind.CHAT,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelId = "chat-model",
            credentialHandle = "chat-secret",
        )
        return OpenAiCompatibleChatClient(
            providers = ProviderProfileSet(
                profiles = listOf(profile),
                selectedProfileIds = mapOf(ProviderKind.CHAT to profile.id),
            ),
            credentials = CredentialResolver { handle ->
                if (handle == "chat-secret") apiKey else null
            },
            client = OkHttpClient(),
        )
    }

    private class RecordingObserver : ChatStreamObserver {
        val output = StringBuilder()
        val finished = CountDownLatch(1)
        @Volatile var failure: ChatStreamFailure? = null
        @Volatile var usage: ChatTokenUsage? = null

        override fun onDelta(text: String) {
            output.append(text)
        }

        override fun onComplete() {
            finished.countDown()
        }

        override fun onUsage(usage: ChatTokenUsage) {
            this.usage = usage
        }

        override fun onFailure(failure: ChatStreamFailure) {
            this.failure = failure
            finished.countDown()
        }
    }
}
