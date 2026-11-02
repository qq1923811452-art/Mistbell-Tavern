package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MemoryExtractionBoundaryTest {
    @Test
    fun overallCallTimeoutIsNotReportedAsCancellation() {
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                call.timeout().timeout(100, TimeUnit.MILLISECONDS)
            }
        }).build()
        withServer(MockResponse().setBody("response").setHeadersDelay(1, TimeUnit.SECONDS)) { server ->
            val result = execute(client(server, http))
            assertEquals("Memory-extraction request timed out.", result.safeMessage)
            assertEquals("timeout", MemoryExtractionFailurePolicy.classify(result.safeMessage, null).safeCategory)
        }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun explicitCancellationHasItsOwnCategory() {
        assertEquals(
            "cancelled",
            MemoryExtractionFailurePolicy.classify("Memory extraction was cancelled.", null).safeCategory,
        )
    }

    @Test
    fun errorDiagnosticsCannotWaitForSlowBody() {
        val response = MockResponse().setResponseCode(400).setBody("{}")
            .setBodyDelay(4, TimeUnit.SECONDS)
        withServer(response) { server ->
            val result = execute(client(server))
            assertEquals(400, result.httpStatus)
            assertTrue(MemoryExtractionFailurePolicy.classify(result.safeMessage, result.httpStatus).permanent)
        }
    }

    @Test
    fun errorDiagnosticsHaveATotalDeadlineEvenWithDrippingBytes() {
        val response = MockResponse().setResponseCode(400).setChunkedBody("{" + " ".repeat(100), 1)
            .throttleBody(1, 50, TimeUnit.MILLISECONDS)
        withServer(response) { server ->
            assertEquals(400, execute(client(server)).httpStatus)
        }
    }

    @Test
    fun unknownLengthErrorBodiesAreLimitedToEightKiB() {
        val bytesRead = AtomicLong()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = requireNotNull(response.body)
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    return super.read(sink, byteCount).also { if (it > 0) bytesRead.addAndGet(it) }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        }.build()
        val diagnostics = mutableListOf<String>()
        val body = "{\"error\":{\"code\":\"context_length_exceeded\"}}" + " ".repeat(20_000)
        withServer(MockResponse().setResponseCode(400).setChunkedBody(body, 1_024)) { server ->
            assertEquals(400, execute(client(server, http, diagnostics)).httpStatus)
            // BufferedSource may prefetch one extra 8 KiB segment, but must not drain 256 KiB.
            assertTrue("read=${bytesRead.get()}", bytesRead.get() <= 16_384)
            assertFalse(diagnostics.any { it.contains("code=context_length_exceeded") })
        }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun untrustedErrorTokensDoNotBecomeDiagnosticText() {
        val diagnostics = mutableListOf<String>()
        val body = "{\"error\":{\"code\":\"用户的私密提示词\",\"type\":\"private_identifier_123\"}}"
        withServer(MockResponse().setResponseCode(400).setBody(body)) { server ->
            execute(client(server, diagnostics = diagnostics))
            assertFalse(diagnostics.any { it.contains("用户的私密提示词") || it.contains("private_identifier") })
            assertTrue(diagnostics.any { it.contains("code=unrecognized") })
        }
    }

    @Test
    fun malformedErrorBodyStillPreservesHttpStatus() {
        withServer(MockResponse().setResponseCode(429).setBody("not JSON")) { server ->
            val failure = execute(client(server))
            assertEquals(429, failure.httpStatus)
            assertEquals(
                "rate_limit",
                MemoryExtractionFailurePolicy.classify(failure.safeMessage, failure.httpStatus).safeCategory,
            )
        }
    }

    private fun client(
        server: MockWebServer,
        http: OkHttpClient = OkHttpClient(),
        diagnostics: MutableList<String> = mutableListOf(),
    ): MemoryExtractionClient {
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
            onDiagnostic = { event, detail -> diagnostics.add("$event|$detail") },
        )
    }

    private fun execute(client: MemoryExtractionClient): MemoryExtractionOutcome.Failure {
        val result = AtomicReference<MemoryExtractionOutcome>()
        val finished = CountDownLatch(1)
        val call = client.extract(
            MemoryExtractionInput(
                conversationId = "conversation-1",
                turns = listOf(EligibleConversationTurn("turn-1", 1)),
                textByTurnId = mapOf("turn-1" to "The user likes tea."),
            ),
            MemoryExtractionObserver { result.set(it); finished.countDown() },
        )
        val completed = finished.await(2, TimeUnit.SECONDS)
        if (!completed) call.cancel()
        assertTrue("Callback exceeded diagnostic deadline", completed)
        return result.get() as MemoryExtractionOutcome.Failure
    }

    private fun withServer(response: MockResponse, block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        // QueueDispatcher.peek() would also throttle the POST upload, before any error headers.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = response
        }
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}
