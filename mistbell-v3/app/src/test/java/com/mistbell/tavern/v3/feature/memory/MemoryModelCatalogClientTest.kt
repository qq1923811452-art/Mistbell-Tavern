package com.mistbell.tavern.v3.feature.memory

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MemoryModelCatalogClientTest {
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
