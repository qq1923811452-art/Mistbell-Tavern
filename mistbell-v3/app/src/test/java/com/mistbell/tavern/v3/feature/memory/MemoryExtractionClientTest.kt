package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MemoryExtractionClientTest {
    @Test
    fun formatsCompletedTurnWithARealLineBreakAndBoundedLength() {
        assertEquals(
            "User: hello\nAssistant: world",
            formatMemoryExtractionTurn("hello", "world"),
        )
        val oversized = formatMemoryExtractionTurn("u".repeat(15_000), "a".repeat(15_000))
        assertEquals(MemoryExtractionInput.MAX_TURN_TEXT_CHARS, oversized.length)
        assertTrue(oversized.startsWith("User: u"))
        assertTrue(oversized.contains("\nAssistant: "))
        assertTrue(oversized.substringAfter("\nAssistant: ").length > 9_000)

        val longUserWithShortReply = formatMemoryExtractionTurn("u".repeat(25_000), "ok")
        assertEquals(MemoryExtractionInput.MAX_TURN_TEXT_CHARS, longUserWithShortReply.length)
        assertTrue(longUserWithShortReply.endsWith("\nAssistant: ok"))
    }

    @Test
    fun usesOnlySelectedMemoryProviderAndValidatesFactSource() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"facts\":[{\"subject\":\"user\",\"predicate\":\"likes\"""" +
                    """,\"value\":\"tea\",\"source_turn_id\":\"turn-1\"}]}"}}]}""",
            ),
        )
        server.start()
        try {
            val profiles =
                ProviderProfileSet(
                    profiles =
                        listOf(
                            ProviderProfile(
                                id = "chat",
                                kind = ProviderKind.CHAT,
                                baseUrl = server.url("/chat-v1").toString(),
                                modelId = "chat-model",
                                credentialHandle = "chat-key",
                            ),
                            ProviderProfile(
                                id = "memory",
                                kind = ProviderKind.MEMORY_EXTRACTION,
                                baseUrl = server.url("/v1").toString(),
                                modelId = "memory-model",
                                credentialHandle = "memory-key",
                            ),
                        ),
                    selectedProfileIds =
                        mapOf(
                            ProviderKind.CHAT to "chat",
                            ProviderKind.MEMORY_EXTRACTION to "memory",
                        ),
                )
            val client =
                MemoryExtractionClient(
                    profiles,
                    credentials =
                        MemoryCredentialResolver { handle ->
                            when (handle) {
                                "chat-key" -> "chat-secret"
                                "memory-key" -> "memory-secret"
                                else -> null
                            }
                        },
                )
            val outcome = execute(client)
            assertTrue(outcome is MemoryExtractionOutcome.Success)
            val success = outcome as MemoryExtractionOutcome.Success
            assertEquals("tea", success.result.facts.single().value)
            assertEquals("turn-1", success.result.facts.single().sourceTurnId)

            val request = server.takeRequest(2, TimeUnit.SECONDS)
            requireNotNull(request)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer memory-secret", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"model\":\"memory-model\""))
            assertTrue(body.contains("\"temperature\":0.1"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun acceptsFactsWrappedInMarkdownFenceOrPreamble() {
        val server = MockWebServer()
        val fenced =
            "```json\n" +
                """{"facts":[{"subject":"user","predicate":"likes","value":"tea","source_turn_id":"turn-1"}]}""" +
                "\n```"
        val envelope =
            org.json.JSONObject()
                .put(
                    "choices",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put(
                            "message",
                            org.json.JSONObject().put("content", "Here is the result:\n$fenced"),
                        ),
                    ),
                )
        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope.toString()))
        server.start()
        try {
            val outcome = execute(clientFor(server))
            assertTrue(outcome is MemoryExtractionOutcome.Success)
            assertEquals("tea", (outcome as MemoryExtractionOutcome.Success).result.facts.single().value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stripsThinkBlocksBeforeParsingAndResolvesShortTurnAliases() {
        val server = MockWebServer()
        val content =
            "<think>{\"facts\":[{\"subject\":\"user\",\"predicate\":\"likes\"," +
                "\"value\":\"tea\",\"source_turn_id\":\"invented\"}]}</think>\n" +
                """{"facts":[{"subject":"user","predicate":"likes","value":"tea","source_turn_id":"t1"}]}"""
        val envelope =
            org.json.JSONObject()
                .put(
                    "choices",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put("message", org.json.JSONObject().put("content", content)),
                    ),
                )
        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope.toString()))
        server.start()
        try {
            val outcome = execute(clientFor(server))
            assertTrue(outcome is MemoryExtractionOutcome.Success)
            assertEquals(
                "turn-1",
                (outcome as MemoryExtractionOutcome.Success).result.facts.single().sourceTurnId,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun keepsValidFactsWhenAnotherFactReferencesAnUnknownTurn() {
        val server = MockWebServer()
        val facts =
            org.json.JSONArray()
                .put(
                    org.json.JSONObject()
                        .put("subject", "user")
                        .put("predicate", "likes")
                        .put("value", "tea")
                        .put("source_turn_id", "turn-1"),
                )
                .put(
                    org.json.JSONObject()
                        .put("subject", "user")
                        .put("predicate", "likes")
                        .put("value", "coffee")
                        .put("source_turn_id", "invented-turn"),
                )
        val content = org.json.JSONObject().put("facts", facts).toString()
        val envelope =
            org.json.JSONObject()
                .put(
                    "choices",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put("message", org.json.JSONObject().put("content", content)),
                    ),
                )
        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope.toString()))
        server.start()
        try {
            val outcome = execute(clientFor(server))
            assertTrue(outcome is MemoryExtractionOutcome.Success)
            val facts = (outcome as MemoryExtractionOutcome.Success).result.facts
            assertEquals(1, facts.size)
            assertEquals("tea", facts.single().value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsFactThatReferencesUnknownSourceTurn() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"facts\":[{\"subject\":\"user\",\"predicate\":\"likes\"""" +
                    """,\"value\":\"tea\",\"source_turn_id\":\"not-a-real-turn\"}]}"}}]}""",
            ),
        )
        server.start()
        try {
            val profiles =
                ProviderProfileSet(
                    profiles =
                        listOf(
                            ProviderProfile(
                                id = "memory",
                                kind = ProviderKind.MEMORY_EXTRACTION,
                                baseUrl = server.url("/v1").toString(),
                                modelId = "memory-model",
                                credentialHandle = "memory-key",
                            ),
                        ),
                    selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to "memory"),
                )
            val outcome =
                execute(
                    MemoryExtractionClient(profiles, credentials = MemoryCredentialResolver { "memory-secret" }),
                )
            assertTrue(outcome is MemoryExtractionOutcome.Failure)
            assertEquals(
                "Memory-extraction response failed validation.",
                (outcome as MemoryExtractionOutcome.Failure).safeMessage,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bodyReadFailureFinishesOnceAndClosesResponse() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("partial response"))
        server.start()
        val closes = AtomicInteger()
        val callbacks = AtomicInteger()
        val http =
            OkHttpClient.Builder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = requireNotNull(response.body)
                val brokenSource =
                    object : ForwardingSource(body.source()) {
                        private var firstRead = true

                        override fun read(
                            sink: Buffer,
                            byteCount: Long,
                        ): Long {
                            if (!firstRead) throw IOException("private response text must not escape")
                            firstRead = false
                            return super.read(sink, minOf(1L, byteCount))
                        }

                        override fun close() {
                            closes.incrementAndGet()
                            super.close()
                        }
                    }.buffer()
                response.newBuilder().body(
                    object : ResponseBody() {
                        override fun contentType() = body.contentType()

                        override fun contentLength() = -1L

                        override fun source() = brokenSource
                    },
                ).build()
            }.build()
        try {
            val outcome = execute(clientFor(server, http), callbacks)
            assertEquals(MemoryExtractionOutcome.Failure("Memory-extraction request failed."), outcome)
            assertEquals(1, callbacks.get())
            assertEquals(1, closes.get())
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun cancellationWhileReadingFinishesOnce() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("response").setBodyDelay(1, TimeUnit.SECONDS))
        server.start()
        val callbacks = AtomicInteger()
        val result = AtomicReference<MemoryExtractionOutcome>()
        val finished = CountDownLatch(1)
        try {
            val call =
                clientFor(server).extract(
                    input(),
                    MemoryExtractionObserver {
                        callbacks.incrementAndGet()
                        result.set(it)
                        finished.countDown()
                    },
                )
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            call.cancel()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(MemoryExtractionOutcome.Failure("Memory extraction was cancelled."), result.get())
            assertEquals(1, callbacks.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun slowProviderSurvivesAnInheritedShortReadTimeout() {
        // The client used to inherit the caller's read timeout (OkHttp defaults to 10 s), so a
        // batch whose non-streamed answer needed longer died as a "network" failure.
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"{\"facts\":[]}"}}]}""")
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )
        server.start()
        val shortClient = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        try {
            assertEquals(
                MemoryExtractionOutcome.Success(MemoryExtractionResult(emptyList())),
                execute(clientFor(server, shortClient)),
            )
        } finally {
            server.shutdown()
            shortClient.dispatcher.executorService.shutdown()
            shortClient.connectionPool.evictAll()
        }
    }

    @Test
    fun socketTimeoutIsReportedAsTimeoutInsteadOfNetwork() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[]}""").setBodyDelay(2, TimeUnit.SECONDS))
        server.start()
        val profile =
            ProviderProfile(
                id = "memory",
                kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = server.url("/v1").toString(),
                modelId = "memory-model",
            )
        try {
            val client =
                MemoryExtractionClient(
                    ProviderProfileSet(listOf(profile), mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id)),
                    MemoryCredentialResolver { null },
                    readTimeoutMillis = 200L,
                )
            val outcome = execute(client)
            assertEquals(MemoryExtractionOutcome.Failure("Memory-extraction request timed out."), outcome)
            val failure = requireNotNull(outcome as? MemoryExtractionOutcome.Failure)
            assertEquals(
                "timeout",
                MemoryExtractionFailurePolicy.classify(failure.safeMessage, failure.httpStatus).safeCategory,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun providerErrorCodeIsDiagnosedWithoutLeakingMessageOrPrompt() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"context_length_exceeded","message":"private prompt text"}}""",
            ),
        )
        server.start()
        val diagnostics = mutableListOf<String>()
        val profile =
            ProviderProfile(
                id = "memory",
                kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = server.url("/v1").toString(),
                modelId = "memory-model",
            )
        try {
            val client =
                MemoryExtractionClient(
                    ProviderProfileSet(listOf(profile), mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id)),
                    MemoryCredentialResolver { null },
                    onDiagnostic = { event, detail -> diagnostics += "$event|$detail" },
                )
            assertEquals(
                MemoryExtractionOutcome.Failure("Memory-extraction provider returned HTTP 400.", 400),
                execute(client),
            )
            val httpError = diagnostics.single { it.startsWith("memory.extraction.http_error") }
            assertTrue(httpError.contains("status=400"))
            assertTrue(httpError.contains("code=context_length_exceeded"))
            assertTrue(diagnostics.none { it.contains("private prompt text") })
            assertTrue(diagnostics.single { it.startsWith("memory.extraction.request.started") }.contains("turns=1"))
        } finally {
            server.shutdown()
        }
    }

    private fun clientFor(
        server: MockWebServer,
        http: OkHttpClient = OkHttpClient(),
    ): MemoryExtractionClient {
        val profile =
            ProviderProfile(
                id = "memory",
                kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = server.url("/v1").toString(),
                modelId = "memory-model",
            )
        return MemoryExtractionClient(
            ProviderProfileSet(listOf(profile), mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id)),
            MemoryCredentialResolver { null },
            http,
        )
    }

    private fun input() =
        MemoryExtractionInput(
            conversationId = "conversation-1",
            turns = listOf(EligibleConversationTurn("turn-1", 1)),
            textByTurnId = mapOf("turn-1" to "The user likes tea."),
        )

    private fun execute(
        client: MemoryExtractionClient,
        callbacks: AtomicInteger = AtomicInteger(),
    ): MemoryExtractionOutcome {
        val latch = CountDownLatch(1)
        val result = AtomicReference<MemoryExtractionOutcome>()
        client.extract(
            MemoryExtractionInput(
                conversationId = "conversation-1",
                turns = listOf(EligibleConversationTurn("turn-1", 1)),
                textByTurnId = mapOf("turn-1" to "The user likes tea."),
            ),
            MemoryExtractionObserver {
                callbacks.incrementAndGet()
                result.set(it)
                latch.countDown()
            },
        )
        assertTrue("Memory extraction callback timed out.", latch.await(3, TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }
}
