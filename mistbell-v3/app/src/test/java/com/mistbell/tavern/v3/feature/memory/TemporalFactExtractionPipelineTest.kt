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

    @Test
    fun expiredAttemptRejectsLatePipelineBeforeFactsOrTextsAreWritten() = kotlinx.coroutines.runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(extractionCompletionBody()))
        server.start()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val facts = InMemoryFacts()
        val texts = InMemoryTexts()
        val attempt = MemoryExtractionAttempt()
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        try {
            val profile = ProviderProfile(
                id = "memory", kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = server.url("/v1").toString(), modelId = "memory-model", credentialHandle = "memory-key",
            )
            val client = MemoryExtractionClient(
                providers = ProviderProfileSet(
                    profiles = listOf(profile),
                    selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
                ),
                credentials = MemoryCredentialResolver { "test-secret" },
            )
            TemporalFactExtractionPipeline(client, facts, texts).extractAndPersist(
                input = MemoryExtractionInput(
                    conversationId = "scope-1",
                    turns = listOf(EligibleConversationTurn("turn-1", 1)),
                    textByTurnId = mapOf("turn-1" to "The user likes tea."),
                ),
                commitGate = { commit ->
                    entered.countDown()
                    try {
                        check(release.await(5, TimeUnit.SECONDS))
                        attempt.commit(commit)
                    } finally {
                        finished.countDown()
                    }
                },
            ) { callbacks.incrementAndGet() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            try {
                attempt.await(1)
            } catch (_: java.io.IOException) {
                // The response has arrived but must not commit after this expiry.
            }
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(TemporalFactLoadResult.Missing, facts.load())
            assertEquals(MemoryTextLoadResult.Missing, texts.load())
            assertEquals(0, callbacks.get())
        } finally {
            release.countDown()
            server.shutdown()
        }
    }

    @Test
    fun persistenceUsesAtomicUpdateInsteadOfSeparateLoadAndSave() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(extractionCompletionBody()))
        server.start()
        val saved = java.util.concurrent.atomic.AtomicReference<TemporalFactPipelineOutcome>()
        val latch = CountDownLatch(1)
        try {
            val profile = ProviderProfile(
                id = "memory", kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = server.url("/v1").toString(), modelId = "memory-model", credentialHandle = "memory-key",
            )
            val client = MemoryExtractionClient(
                providers = ProviderProfileSet(
                    profiles = listOf(profile),
                    selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
                ),
                credentials = MemoryCredentialResolver { "test-secret" },
            )
            val facts = UpdateOnlyFacts()
            TemporalFactExtractionPipeline(client, facts, InMemoryTexts()).extractAndPersist(
                MemoryExtractionInput(
                    conversationId = "scope-1",
                    turns = listOf(EligibleConversationTurn("turn-1", 1)),
                    textByTurnId = mapOf("turn-1" to "The user likes tea."),
                ),
            ) { saved.set(it); latch.countDown() }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertTrue(saved.get() is TemporalFactPipelineOutcome.Saved)
            assertEquals(1, facts.updateCalls)
        } finally {
            server.shutdown()
        }
    }

    private fun extractionCompletionBody(): String {
        val content = """{"facts":[{"subject":"user","predicate":"likes","value":"tea","source_turn_id":"turn-1"}]}"""
        return org.json.JSONObject().put(
            "choices", org.json.JSONArray().put(
                org.json.JSONObject().put("message", org.json.JSONObject().put("content", content)),
            ),
        ).toString()
    }

    /** Any load/save fallback would bypass the concurrency contract and fail this test. */
    private class UpdateOnlyFacts : TemporalFactPersistence {
        var updateCalls = 0
        private var current: List<TemporalMemoryFact> = emptyList()

        override fun update(
            transform: (List<TemporalMemoryFact>) -> List<TemporalMemoryFact>,
        ): List<TemporalMemoryFact> {
            updateCalls += 1
            current = transform(current)
            return current
        }

        override fun save(facts: List<TemporalMemoryFact>) = error("save must not be used")

        override fun load(): TemporalFactLoadResult = error("load must not be used")
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
