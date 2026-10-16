package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.feature.memory.MemoryCredentialResolver
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
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

    private fun execute(client: MemoryExtractionClient): MemoryExtractionOutcome {
        val latch = CountDownLatch(1)
        val result = AtomicReference<MemoryExtractionOutcome>()
        client.extract(
            MemoryExtractionInput(
                conversationId = "conversation-1",
                turns = listOf(EligibleConversationTurn("turn-1", 1)),
                textByTurnId = mapOf("turn-1" to "The user likes tea."),
            ),
            MemoryExtractionObserver {
                result.set(it)
                latch.countDown()
            },
        )
        assertTrue("Memory extraction callback timed out.", latch.await(3, TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }
}
