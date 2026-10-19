package com.mistbell.tavern.v3.feature.worldbook

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWorldBookServiceTest {
    private fun entry(id: String, key: List<String>, content: String = id,
                      constant: Boolean = false, probability: Double = 1.0, order: Int = 0) =
        WorldBookEntry(id, "book", keys = key, content = content, constant = constant,
            probability = probability, order = order)

    @Test fun matchesLiteralRegexAndWildcardKeys() {
        val service = LocalWorldBookService()
        val results = service.activateEntries(
            listOf(entry("literal", listOf("dragon")), entry("regex", listOf("/knight\\\\d+/")),
                entry("wildcard", listOf("ancient*stone"))),
            "Dragon knight42 near an ancient forgotten stone",
        )
        assertEquals(listOf("literal", "regex", "wildcard"), results.map { it.entry.id })
    }

    @Test fun probabilityAndBudgetAreDeterministicWhenRandomIsInjected() {
        val service = LocalWorldBookService(randomDouble = { 0.8 })
        val results = service.activateEntries(
            listOf(entry("probability", listOf("key"), probability = 0.5),
                entry("budget", listOf("key"), content = "123456")),
            "key", maxBudget = 5,
        )
        assertEquals(emptyList<ActivatedWorldBookEntry>(), results)
    }

    @Test fun historyScanDepthUsesOnlyMostRecentItems() {
        val service = LocalWorldBookService()
        val corpus = service.buildScanCorpus("current", listOf("old", "middle", "recent"), 2)
        assertEquals(false, service.matchingKeys(listOf("old"), corpus).isNotEmpty())
        assertEquals(true, service.matchingKeys(listOf("recent"), corpus).isNotEmpty())
    }
}
