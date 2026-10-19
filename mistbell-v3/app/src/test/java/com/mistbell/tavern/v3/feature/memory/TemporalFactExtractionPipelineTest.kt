package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EligibleConversationTurn
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TemporalFactExtractionPipelineTest {
    @Test
    fun preservesProviderHttpStatusForQueueRetryClassification() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("private provider response"))
        server.start()
        try {
            val profile = ProviderProfile(
                id = "memory",
                kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = server.url("/v1").toString(),
                modelId = "memory-model",
                credentialHandle = "memory-key",
            )
            val client = MemoryExtractionClient(
                providers = ProviderProfileSet(
                    profiles = listOf(profile),
                    selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
                ),
                credentials = MemoryCredentialResolver { "test-secret" },
            )
            val pipeline = TemporalFactExtractionPipeline(
                client = client,
                factStore = InMemoryFacts(),
                memoryTextStore = InMemoryTexts(),
            )
            val result = AtomicReference<TemporalFactPipelineOutcome>()
            val latch = CountDownLatch(1)
            pipeline.extractAndPersist(
                MemoryExtractionInput(
                    conversationId = "scope-1",
                    turns = listOf(EligibleConversationTurn("turn-1", 1)),
                    textByTurnId = mapOf("turn-1" to "The user likes tea."),
                ),
            ) {
                result.set(it)
                latch.countDown()
            }

            assertTrue("Pipeline callback timed out.", latch.await(3, TimeUnit.SECONDS))
            val failed = result.get() as TemporalFactPipelineOutcome.Failed
            assertEquals(401, failed.httpStatus)
            assertEquals("Memory-extraction provider returned HTTP 401.", failed.safeMessage)
            assertTrue(!failed.safeMessage.contains("private provider response"))
        } finally {
            server.shutdown()
        }
    }

    private class InMemoryFacts : TemporalFactPersistence {
        private var facts: List<TemporalMemoryFact>? = null

        override fun save(facts: List<TemporalMemoryFact>) {
            this.facts = facts.toList()
        }

        override fun load(): TemporalFactLoadResult =
            facts?.let(TemporalFactLoadResult::Loaded) ?: TemporalFactLoadResult.Missing
    }

    private class InMemoryTexts : MemoryTextPersistence {
        private var records: List<MemoryTextRecord>? = null

        override fun save(records: List<MemoryTextRecord>) {
            this.records = records.toList()
        }

        override fun load(): MemoryTextLoadResult =
            records?.let(MemoryTextLoadResult::Loaded) ?: MemoryTextLoadResult.Missing

        override fun update(transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>): List<MemoryTextRecord> {
            val updated = transform(loadMemoryTexts())
            save(updated)
            return updated
        }

        override fun loadMemoryTexts(): List<MemoryTextRecord> = records?.toList() ?: emptyList()
    }
}
