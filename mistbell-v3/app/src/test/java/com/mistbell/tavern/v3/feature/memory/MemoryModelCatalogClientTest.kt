package com.mistbell.tavern.v3.feature.memory

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MemoryModelCatalogClientTest {
    @Test
    fun timeoutCancelsTheCallWhileWaitingForHeaders(): Unit =
        runBlocking {
            assertTimeoutCancelsRequest(
                MockResponse().setHeadersDelay(3, TimeUnit.SECONDS)
                    .setBody("""{"data":[{"id":"late"}]}"""),
            )
        }

    @Test
    fun timeoutCancelsTheCallWhileReadingTheBody(): Unit =
        runBlocking {
            assertTimeoutCancelsRequest(
                MockResponse().setBodyDelay(3, TimeUnit.SECONDS)
                    .setBody("""{"data":[{"id":"late"}]}"""),
            )
        }

    private suspend fun assertTimeoutCancelsRequest(response: MockResponse) {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        val callRef = AtomicReference<Call>()
        val finished = CountDownLatch(1)
        val httpClient =
            OkHttpClient.Builder().eventListener(
                object : EventListener() {
                    override fun callStart(call: Call) {
                        callRef.set(call)
                    }

                    override fun callFailed(
                        call: Call,
                        ioe: java.io.IOException,
                    ) {
                        finished.countDown()
                    }
                },
            ).build()
        val idle = CountDownLatch(1)
        httpClient.dispatcher.idleCallback = Runnable { idle.countDown() }
        try {
            val failure =
                runCatching {
                    withTimeout(500L) {
                        MemoryModelCatalogClient(httpClient).fetch(server.url("/v1").toString(), null)
                    }
                }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            assertTrue(requireNotNull(callRef.get()).isCanceled())
            assertTrue("Cancelled HTTP call should release promptly", finished.await(1, TimeUnit.SECONDS))
            assertTrue("Cancelled callback should return promptly", idle.await(1, TimeUnit.SECONDS))
            assertEquals(0, httpClient.dispatcher.runningCallsCount())
        } finally {
            server.shutdown()
            httpClient.dispatcher.executorService.shutdownNow()
            httpClient.connectionPool.evictAll()
        }
    }

    @Test
    fun fetchesDeduplicatedModelIdsAndOmitsAuthorizationWithoutAKey(): Unit =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[{"id":"deepseek-chat"},{"id":"deepseek-flash"},{"id":"deepseek-chat"}]}""",
                ),
            )
            server.start()
            try {
                val models = MemoryModelCatalogClient().fetch(server.url("/v1").toString(), apiKey = null)
                assertEquals(listOf("deepseek-chat", "deepseek-flash"), models)
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("/v1/models", request.path)
                assertNull(request.getHeader("Authorization"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun sendsBearerCredentialAndRejectsHttpFailures(): Unit =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"models":[{"id":"memory-model"}]}"""),
            )
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            server.start()
            try {
                val client = MemoryModelCatalogClient()
                assertEquals(listOf("memory-model"), client.fetch(server.url("/v1").toString(), "test-secret"))
                val authorized = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("Bearer test-secret", authorized.getHeader("Authorization"))

                val failure =
                    runCatching {
                        client.fetch(server.url("/v1").toString(), "test-secret")
                    }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("HTTP 401"))
            } finally {
                server.shutdown()
            }
        }
}
