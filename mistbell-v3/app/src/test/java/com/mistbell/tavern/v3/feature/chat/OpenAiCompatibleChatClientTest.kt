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
    fun appliesTheDisabledThinkingTemplateWhenThinkingIsOff() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        server.start()
        val profile = ProviderProfile(
            id = "chat",
            kind = ProviderKind.CHAT,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelId = "chat-model",
            credentialHandle = "chat-secret",
            thinkingBodyTemplate = "{\"enable_thinking\":true}",
            thinkingDisabledBody = "{\"enable_thinking\":false}",
        )
        val client = OpenAiCompatibleChatClient(
            providers = ProviderProfileSet(listOf(profile), mapOf(ProviderKind.CHAT to profile.id)),
            credentials = CredentialResolver { "test-chat-key" },
            client = OkHttpClient(),
        )
        client.stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            RecordingObserver(),
            ChatGenerationOptions(thinkingEnabled = false),
        )
        val payload = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertTrue(payload.has("enable_thinking"))
        assertEquals(false, payload.getBoolean("enable_thinking"))
    }

    @Test
    fun reportsLengthWhenTheProviderStopsAtTheTokenLimit() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"part\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n",
                ),
        )
        server.start()
        val observer = RecordingObserver()
        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )
        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFinishReason.LENGTH, observer.finishReason)
    }

    @Test
    fun reportsStopWhenFinishReasonArrivesWithoutDone() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"),
        )
        server.start()
        val observer = RecordingObserver()
        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )
        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFinishReason.STOP, observer.finishReason)
    }

    @Test
    fun keepsUnknownFinishReasonEvenWhenDoneFollows() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()
        val observer = RecordingObserver()
        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )
        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFinishReason.OTHER, observer.finishReason)
    }

    @Test
    fun keepsLengthEvenWhenDoneFollows() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()
        val observer = RecordingObserver()
        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )
        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFinishReason.LENGTH, observer.finishReason)
    }

    @Test
    fun treatsDoneWithoutFinishReasonAsStop() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        server.start()
        val observer = RecordingObserver()
        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )
        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFinishReason.STOP, observer.finishReason)
    }

    @Test
    fun reportsEofWhenNeitherDoneNorFinishReasonArrives() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"),
        )
        server.start()
        val observer = RecordingObserver()
        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )
        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFinishReason.EOF, observer.finishReason)
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

    @Test
    fun keepsReasoningSeparateFromVisibleContentAndReportsReasoningTokens() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"more\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20," +
                        "\"total_tokens\":30,\"completion_tokens_details\":{\"reasoning_tokens\":8}}}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()
        val observer = RecordingObserver()

        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals("answer", observer.output.toString())
        assertEquals("think more", observer.reasoning.toString())
        assertEquals(8, observer.usage?.reasoningTokens)
    }

    @Test
    fun ignoresNonStringReasoningInNonStreamingResponses() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"choices\":[{\"message\":{\"content\":\"hi\",\"reasoning_content\":42}}]," +
                        "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}",
                ),
        )
        server.start()
        val observer = RecordingObserver()

        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(stream = false),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals("hi", observer.output.toString())
        assertEquals("", observer.reasoning.toString())
    }

    @Test
    fun readsGeminiNativeNonStreamingCompletions() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"candidates\":[{\"content\":{\"parts\":[" +
                        "{\"text\":\"你好\"},{\"text\":\"呀\"}]}}]}",
                ),
        )
        server.start()
        val observer = RecordingObserver()

        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(stream = false),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals("你好呀", observer.output.toString())
    }

    @Test
    fun joinsArrayContentPartsInNonStreamingCompletions() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"choices\":[{\"message\":{\"content\":[" +
                        "{\"type\":\"text\",\"text\":\"A\"}," +
                        "{\"type\":\"text\",\"text\":\"B\"}]}}]}",
                ),
        )
        server.start()
        val observer = RecordingObserver()

        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(stream = false),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals("AB", observer.output.toString())
    }

    @Test
    fun joinsArrayContentPartsInStreamingDeltas() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":[" +
                        "{\"text\":\"Hi\"},{\"text\":\"!\"}]}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()
        val observer = RecordingObserver()

        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals("Hi!", observer.output.toString())
    }

    @Test
    fun surfacesProviderErrorsFromNonStreamingResponses() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"model gemini is not available\"}}"),
        )
        server.start()
        val observer = RecordingObserver()

        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(stream = false),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFailureKind.INVALID_STREAM, observer.failure?.kind)
        assertEquals("model gemini is not available", observer.failure?.safeMessage)
    }

    @Test
    fun surfacesProviderErrorsFromStreamingEvents() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"error\":{\"message\":\"quota exceeded\"}}\n\n"),
        )
        server.start()
        val observer = RecordingObserver()

        clientFor(server, apiKey = "key").stream(
            listOf(PromptMessage(PromptRole.USER, "Hi")),
            observer,
            ChatGenerationOptions(),
        )

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(ChatFailureKind.INVALID_STREAM, observer.failure?.kind)
        assertEquals("quota exceeded", observer.failure?.safeMessage)
    }

    private class RecordingObserver : ChatStreamObserver {
        val output = StringBuilder()
        val reasoning = StringBuilder()
        val finished = CountDownLatch(1)
        @Volatile var failure: ChatStreamFailure? = null
        @Volatile var usage: ChatTokenUsage? = null
        @Volatile var finishReason: ChatFinishReason? = null

        override fun onDelta(text: String) {
            output.append(text)
        }

        override fun onReasoningDelta(text: String) {
            reasoning.append(text)
        }

        override fun onComplete() {
            finished.countDown()
        }

        override fun onUsage(usage: ChatTokenUsage) {
            this.usage = usage
        }

        override fun onFinish(reason: ChatFinishReason) {
            finishReason = reason
        }

        override fun onFailure(failure: ChatStreamFailure) {
            this.failure = failure
            finished.countDown()
        }
    }
}
