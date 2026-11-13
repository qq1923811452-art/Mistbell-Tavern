package com.mistbell.tavern.v3.feature.worldbook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookSemanticSoftGateTest {
    @Test
    fun selectsOnlyEligibleEntriesAboveTheSimilarityThreshold() =
        runBlocking {
            var calls = 0
            val candidates =
                listOf(
                    entry("a-unique", "alpha-unique"),
                    entry("constant", "constant", constant = true),
                    entry("disabled", "disabled", disable = true),
                    entry("empty", ""),
                )
            val selected =
                WorldbookSemanticSoftGate.select(
                    candidates = candidates,
                    queryVector = listOf(1f, 0f),
                    embedDocuments = { documents ->
                        calls++
                        documents.map { listOf(1f, 0f) }
                    },
                    remainingBudget = 100,
                )

            assertEquals(listOf("a-unique"), selected.map(WorldBookEntry::id))
            assertEquals(1, calls)
        }

    @Test
    fun cachesDocumentVectorsAcrossSelections() =
        runBlocking {
            var calls = 0
            val candidates = listOf(entry("a", "alpha"), entry("b", "beta"))
            val embed: suspend (List<String>) -> List<List<Float>> = { documents ->
                calls++
                documents.map { listOf(1f, 0f) }
            }
            WorldbookSemanticSoftGate.select(candidates, listOf(1f, 0f), embed, 100)
            WorldbookSemanticSoftGate.select(candidates, listOf(1f, 0f), embed, 100)
            assertEquals(1, calls)
        }

    @Test
    fun respectsRemainingCharacterBudget() =
        runBlocking {
            val selected =
                WorldbookSemanticSoftGate.select(
                    candidates = listOf(entry("a", "abcdefghij")),
                    queryVector = listOf(1f, 0f),
                    embedDocuments = { listOf(listOf(1f, 0f)) },
                    remainingBudget = 5,
                )
            assertTrue(selected.isEmpty())
        }

    private fun entry(
        id: String,
        content: String,
        constant: Boolean = false,
        disable: Boolean = false,
    ) = WorldBookEntry(id = id, bookId = "book", content = content, constant = constant, disable = disable)
}
