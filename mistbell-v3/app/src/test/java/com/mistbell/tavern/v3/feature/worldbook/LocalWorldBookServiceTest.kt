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
        val scanText = "Dragon knight42 near an ancient forgotten stone"
        assertEquals(listOf("dragon"), service.matchingKeys(listOf("dragon"), scanText))
        assertEquals(listOf("/knight[0-9]+/"), service.matchingKeys(listOf("/knight[0-9]+/"), scanText))
        assertEquals(listOf("ancient*stone"), service.matchingKeys(listOf("ancient*stone"), scanText))
        val results = service.activateEntries(
            listOf(entry("literal", listOf("dragon"), order = 1), entry("regex", listOf("/knight[0-9]+/"), order = 2),
                entry("wildcard", listOf("ancient*stone"), order = 3)),
            scanText,
        )
        assertEquals(setOf("literal", "regex", "wildcard"), results.map { it.entry.id }.toSet())
    }

    @Test fun probabilityAndBudgetAreDeterministicWhenRandomIsInjected() {
        val service = LocalWorldBookService(randomDouble = { 0.8 })
        val report = service.activateEntriesWithReport(
            listOf(entry("probability", listOf("key"), probability = 0.5),
                entry("budget", listOf("key"), content = "123456")),
            "key", maxBudget = 5,
        )
        assertEquals(emptyList<ActivatedWorldBookEntry>(), report.activatedEntries)
        assertEquals(1, report.droppedByBudget)
    }

    @Test fun stickyEntryCarriesForOneTurnOnly() {
        val service = LocalWorldBookService()
        val state = WorldBookActivationState()
        val sticky = WorldBookEntry(
            id = "sticky", bookId = "book", keys = listOf("dragon"),
            content = "sticky content", sticky = true,
        )

        assertEquals(listOf("sticky"), service.activateEntries(listOf(sticky), "dragon", state = state).map { it.entry.id })
        assertEquals(listOf("sticky"), service.activateEntries(listOf(sticky), "unrelated", state = state).map { it.entry.id })
        assertEquals(emptyList<ActivatedWorldBookEntry>(), service.activateEntries(listOf(sticky), "unrelated", state = state))
    }

    @Test fun delayAndCooldownAreMeasuredInTurns() {
        val service = LocalWorldBookService()
        val delayedState = WorldBookActivationState()
        val delayed = WorldBookEntry(
            id = "delayed", bookId = "book", keys = listOf("dragon"),
            content = "delayed content", delay = 1,
        )
        assertEquals(emptyList<ActivatedWorldBookEntry>(), service.activateEntries(listOf(delayed), "dragon", state = delayedState))
        assertEquals(listOf("delayed"), service.activateEntries(listOf(delayed), "unrelated", state = delayedState).map { it.entry.id })

        val cooldownState = WorldBookActivationState()
        val cooldown = WorldBookEntry(
            id = "cooldown", bookId = "book", keys = listOf("dragon"),
            content = "cooldown content", cooldown = 1,
        )
        assertEquals(listOf("cooldown"), service.activateEntries(listOf(cooldown), "dragon", state = cooldownState).map { it.entry.id })
        assertEquals(emptyList<ActivatedWorldBookEntry>(), service.activateEntries(listOf(cooldown), "dragon", state = cooldownState))
        assertEquals(listOf("cooldown"), service.activateEntries(listOf(cooldown), "dragon", state = cooldownState).map { it.entry.id })
    }

    @Test fun delayedEntryWaitsUntilItsScheduledTurnEvenWhileKeywordsStillMatch() {
        val service = LocalWorldBookService()
        val state = WorldBookActivationState()
        val delayed = WorldBookEntry(
            id = "delayed-two", bookId = "book", keys = listOf("dragon"),
            content = "delayed content", delay = 2,
        )

        assertEquals(emptyList<ActivatedWorldBookEntry>(), service.activateEntries(listOf(delayed), "dragon", state = state))
        assertEquals(emptyList<ActivatedWorldBookEntry>(), service.activateEntries(listOf(delayed), "dragon", state = state))
        assertEquals(listOf("delayed-two"), service.activateEntries(listOf(delayed), "unrelated", state = state).map { it.entry.id })
    }

    @Test fun historyScanDepthUsesOnlyMostRecentItems() {
        val service = LocalWorldBookService()
        val corpus = service.buildScanCorpus("current", listOf("old", "middle", "recent"), 2)
        assertEquals(false, service.matchingKeys(listOf("old"), corpus).isNotEmpty())
        assertEquals(true, service.matchingKeys(listOf("recent"), corpus).isNotEmpty())
    }

    @Test fun selectiveKeywordsRecursiveActivationAndMutualExclusionWork() {
        val service = LocalWorldBookService()
        val selective = WorldBookEntry(
            id = "selective", bookId = "book", keys = listOf("dragon"),
            secondaryKeys = listOf("castle"), requireSecondaryMatch = true, content = "selective",
        )
        val recursive = WorldBookEntry(
            id = "recursive", bookId = "book", keys = listOf("dragon"),
            content = "a phoenix is mentioned", recursive = true,
        )
        val discovered = WorldBookEntry(
            id = "discovered", bookId = "book", keys = listOf("phoenix"), content = "discovered",
        )
        val groupA = WorldBookEntry(
            id = "group-a", bookId = "book", keys = listOf("dragon"),
            content = "group a", groupName = "exclusive",
        )
        val groupB = WorldBookEntry(
            id = "group-b", bookId = "book", keys = listOf("dragon"),
            content = "group b", groupName = "exclusive",
        )

        val results = service.activateEntries(
            listOf(selective, recursive, discovered, groupA, groupB),
            scanText = "dragon beside a castle",
        )
        assertEquals(setOf("selective", "recursive", "discovered", "group-a"), results.map { it.entry.id }.toSet())
        assertEquals(
            emptyList<ActivatedWorldBookEntry>(),
            service.activateEntries(listOf(selective), scanText = "dragon in the forest"),
        )
    }
}
