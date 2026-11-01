package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                """{"choices":[{"message":{"content":"{\"facts\":[{\"subject\":\"user\",\"predicate\":\"likes\",\"value\":\"tea\",\"source_turn_id\":\"turn-1\"}]}"}}]}""",
            ),
        )
        server.start()
        try {
            val profiles = ProviderProfileSet(
                profiles = listOf(
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
                selectedProfileIds = mapOf(
                    ProviderKind.CHAT to "chat",
                    ProviderKind.MEMORY_EXTRACTION to "memory",
                ),
            )
            val client = MemoryExtractionClient(profiles, credentials = MemoryCredentialResolver { handle ->
                when (handle) {
                    "chat-key" -> "chat-secret"
                    "memory-key" -> "memory-secret"
                    else -> null
                }
            })
            val outcome = execute(client)
            assertTrue(outcome is MemoryExtractionOutcome.Success)
            val success = outcome as MemoryExtractionOutcome.Success
            assertEquals("tea", success.result.facts.single().value)
            assertEquals("turn-1", success.result.facts.single().sourceTurnId)

            val request = server.takeRequest(2, TimeUnit.SECONDS)
            requireNotNull(request)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer memory-secret", request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("\"model\":\"memory-model\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsFactThatReferencesUnknownSourceTurn() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"facts\":[{\"subject\":\"user\",\"predicate\":\"likes\",\"value\":\"tea\",\"source_turn_id\":\"not-a-real-turn\"}]}"}}]}""",
            ),
        )
        server.start()
        try {
            val profiles = ProviderProfileSet(
                profiles = listOf(
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
            val outcome = execute(
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
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = requireNotNull(response.body)
            val brokenSource = object : ForwardingSource(body.source()) {
                private var firstRead = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!firstRead) throw IOException("private response text must not escape")
                    firstRead = false
                    return super.read(sink, minOf(1L, byteCount))
                }
                override fun close() {
                    closes.incrementAndGet()
                    super.close()
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = -1L
                override fun source() = brokenSource
            }).build()
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
            val call = clientFor(server).extract(input(), MemoryExtractionObserver {
                callbacks.incrementAndGet()
                result.set(it)
                finished.countDown()
            })
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            call.cancel()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(MemoryExtractionOutcome.Failure("Memory extraction was cancelled."), result.get())
            assertEquals(1, callbacks.get())
        } finally {
            server.shutdown()
        }
    }

    private fun clientFor(server: MockWebServer, http: OkHttpClient = OkHttpClient()): MemoryExtractionClient {
        val profile = ProviderProfile(
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

    private fun input() = MemoryExtractionInput(
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
