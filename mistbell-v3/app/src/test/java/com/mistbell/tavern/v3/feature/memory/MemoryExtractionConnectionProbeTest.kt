package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionConnectionProbeTest {
    @Test
    fun validEmptyFactsResponseIsASuccessfulConnection(): Unit =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[{"message":{"content":"{\"facts\":[]}"}}]}""",
                ),
            )
            server.start()
            try {
                val result = MemoryExtractionConnectionProbe.run(clientFor(server))
                assertTrue(result.success)
                assertTrue(result.detail.contains("0 条事实"))
                val request = server.takeRequest()
                assertTrue(request.path.orEmpty().endsWith("/chat/completions"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun httpFailureIsReportedWithoutClaimingSuccess(): Unit =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            server.start()
            try {
                val result = MemoryExtractionConnectionProbe.run(clientFor(server))
                assertFalse(result.success)
                assertTrue(result.detail.contains("HTTP 401"))
            } finally {
                server.shutdown()
            }
        }

    private fun clientFor(server: MockWebServer): MemoryExtractionClient {
        val profile =
            ProviderProfile(
                id = "memory-probe",
                kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = server.url("/v1").toString(),
                modelId = "memory-model",
            )
        return MemoryExtractionClient(
            ProviderProfileSet(
                profiles = listOf(profile),
                selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
            ),
            MemoryCredentialResolver { null },
        )
    }
}
