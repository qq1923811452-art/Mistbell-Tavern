package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.TemporalFactObservation
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemorySemanticDeduplicatorTest {
    @Test
    fun alignsParaphrasedFactWithExistingPredicateAndValue() =
        runBlocking {
            val existing = fact("likes", "tea")
            val observation = observation("enjoys", "green tea")

            val result =
                MemorySemanticDeduplicator.deduplicateObservations(
                    observations = listOf(observation),
                    existingCurrent = listOf(existing),
                    embedSimilarity = { texts ->
                        assertEquals(2, texts.size)
                        listOf(listOf(1f, 0f), listOf(0.99f, 0.14f))
                    },
                )

            assertEquals("likes", result.single().predicate)
            assertEquals("tea", result.single().value)
        }

    @Test
    fun keepsDistinctFactWhenSimilarityIsBelowThreshold() =
        runBlocking {
            val observation = observation("dislikes", "coffee")
            val result =
                MemorySemanticDeduplicator.deduplicateObservations(
                    observations = listOf(observation),
                    existingCurrent = listOf(fact("likes", "tea")),
                    embedSimilarity = { listOf(listOf(1f, 0f), listOf(0f, 1f)) },
                )

            assertEquals(observation, result.single())
        }

    @Test
    fun returnsOriginalObservationsWithoutEmbeddingFunction() =
        runBlocking {
            val observation = observation("likes", "tea")
            assertEquals(
                listOf(observation),
                MemorySemanticDeduplicator.deduplicateObservations(listOf(observation), listOf(fact("likes", "tea"))),
            )
        }

    private fun fact(
        predicate: String,
        value: String,
    ) = TemporalMemoryFact(
        id = "fact-1",
        conversationId = "conversation-1",
        sourceTurnId = "turn-1",
        subject = "user",
        predicate = predicate,
        value = value,
        observedAtEpochMillis = 1L,
        validFromEpochMillis = 1L,
    )

    private fun observation(
        predicate: String,
        value: String,
    ) = TemporalFactObservation(
        conversationId = "conversation-1",
        sourceTurnId = "turn-2",
        subject = "user",
        predicate = predicate,
        value = value,
        observedAtEpochMillis = 2L,
    )
}
