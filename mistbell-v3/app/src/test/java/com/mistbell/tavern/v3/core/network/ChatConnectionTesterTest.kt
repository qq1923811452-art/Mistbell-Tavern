package com.mistbell.tavern.v3.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConnectionTesterTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun reportsSuccessForAnAuthorizedMinimalRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        val result = ChatConnectionTester().test(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelId = "test-model",
            apiKey = "test-key",
        )
        assertTrue(result.message, result.success)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/chat/completions"))
        assertTrue(recorded.getHeader("Authorization") == "Bearer test-key")
    }

    @Test
    fun reportsHttpFailureWithoutThrowing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val result = ChatConnectionTester().test(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelId = "test-model",
            apiKey = "bad-key",
        )
        assertFalse(result.success)
        assertTrue(result.message.contains("401"))
    }

    @Test
    fun rejectsMissingFieldsBeforeSending() = runBlocking {
        assertFalse(ChatConnectionTester().test("", "model", "key").success)
        assertFalse(ChatConnectionTester().test("https://example.com", "", "key").success)
        assertFalse(ChatConnectionTester().test("https://example.com", "model", "").success)
    }
}
