package com.mistbell.tavern.v3.feature.worldbook.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookPlannerTest {

    private val planner = WorldbookPlanner(randomDouble = { 0.5 })

    private fun entry(
        id: String,
        content: String = "content-$id",
        keys: List<String> = emptyList(),
        secondaryKeys: List<String> = emptyList(),
        requireSecondary: Boolean = false,
        constant: Boolean = false,
        recursive: Boolean = false,
        probability: Double = 1.0,
        sticky: Boolean = false,
        cooldown: Int = 0,
        delay: Int = 0,
        group: String = "",
        order: Int = 0,
        disabled: Boolean = false,
    ) = WorldbookEntry(
        id = id,
        comment = id,
        keys = keys,
        secondaryKeys = secondaryKeys,
        requireSecondaryMatch = requireSecondary,
        recursive = recursive,
        content = content,
        constant = constant,
        disabled = disabled,
        order = order,
        probability = probability,
        sticky = sticky,
        cooldown = cooldown,
        delay = delay,
        groupName = group,
    )

    @Test
    fun constantEntriesActivateWithoutKeywords() {
        val plan = planner.activate(listOf(entry("a", constant = true)), scanText = "hello")
        assertEquals(listOf("a"), plan.activated.map { it.entry.id })
    }

    @Test
    fun keywordEntriesActivateOnChineseSubstring() {
        val plan = planner.activate(
            listOf(entry("a", keys = listOf("北京"))),
            scanText = "我今天去了北京",
        )
        assertEquals(listOf("a"), plan.activated.map { it.entry.id })
        assertEquals(listOf("北京"), plan.activated.single().matchedKeys)
    }

    @Test
    fun secondaryKeysAreRequiredWhenSelective() {
        val entries = listOf(entry("a", keys = listOf("火"), secondaryKeys = listOf("剑"), requireSecondary = true))
        assertEquals(0, planner.activate(entries, "火球术").activated.size)
        assertEquals(1, planner.activate(entries, "火球术与剑").activated.size)
    }

    @Test
    fun budgetDropsOversizedEntries() {
        val plan = planner.activate(
            listOf(
                entry("a", content = "x".repeat(20), keys = listOf("trigger")),
                entry("b", content = "y".repeat(20), keys = listOf("trigger")),
            ),
            scanText = "trigger",
            maxBudget = 30,
            state = null,
        )
        assertEquals(1, plan.activated.size)
        assertEquals(1, plan.droppedByBudget)
    }

    @Test
    fun recursionExpandsOnceAndStops() {
        val entries = listOf(
            entry("a", keys = listOf("alpha"), content = "alpha introduces beta", recursive = true),
            entry("b", keys = listOf("beta"), content = "beta introduces gamma", recursive = true),
            entry("c", keys = listOf("gamma"), content = "gamma"),
        )
        val plan = planner.activate(entries, scanText = "alpha", maxRecursiveDepth = 4)
        assertEquals(listOf("a", "b", "c").sorted(), plan.activated.map { it.entry.id }.sorted())
    }

    @Test
    fun recursionDepthLimitIsRespected() {
        val entries = listOf(
            entry("a", keys = listOf("alpha"), content = "beta", recursive = true),
            entry("b", keys = listOf("beta"), content = "gamma", recursive = true),
            entry("c", keys = listOf("gamma"), content = "done"),
        )
        val plan = planner.activate(entries, scanText = "alpha", maxRecursiveDepth = 0)
        assertEquals(listOf("a"), plan.activated.map { it.entry.id })
    }

    @Test
    fun groupActivatesOnlyOneEntry() {
        val entries = listOf(
            entry("a", keys = listOf("alpha"), group = "g", order = 0),
            entry("b", keys = listOf("alpha"), group = "g", order = 1),
        )
        val plan = planner.activate(entries, scanText = "alpha")
        assertEquals(1, plan.activated.size)
        assertEquals("a", plan.activated.single().entry.id)
    }

    @Test
    fun probabilityZeroNeverActivatesAndOneAlwaysDoes() {
        val never = planner.activate(listOf(entry("a", keys = listOf("x"), probability = 0.0)), "x")
        assertEquals(0, never.activated.size)
        val always = planner.activate(listOf(entry("b", keys = listOf("x"), probability = 1.0)), "x")
        assertEquals(1, always.activated.size)
    }

    @Test
    fun stickyEntryCarriesToNextTurnWithoutKeyword() {
        val state = WorldbookPlanner.WorldbookActivationState()
        val entries = listOf(entry("a", keys = listOf("trigger"), sticky = true))
        val first = planner.activate(entries, "trigger", state = state)
        val second = planner.activate(entries, "unrelated", state = state)
        assertEquals(1, first.activated.size)
        assertEquals(1, second.activated.size)
        assertTrue(second.activated.single().carriedBySticky)
    }

    @Test
    fun cooldownBlocksReactivation() {
        val state = WorldbookPlanner.WorldbookActivationState()
        val entries = listOf(entry("a", keys = listOf("trigger"), cooldown = 5))
        assertEquals(1, planner.activate(entries, "trigger", state = state).activated.size)
        assertEquals(0, planner.activate(entries, "trigger", state = state).activated.size)
    }

    @Test
    fun regexKeysAndWildcardsMatch() {
        assertEquals(1, planner.activate(listOf(entry("a", keys = listOf("/\\d{3}/"))), "code 123").activated.size)
        assertEquals(1, planner.activate(listOf(entry("b", keys = listOf("he*lo"))), "hello").activated.size)
    }

    @Test
    fun disabledEntriesAreIgnored() {
        val plan = planner.activate(
            listOf(entry("a", keys = listOf("x"), disabled = true), entry("b", constant = true)),
            "x",
        )
        assertEquals(listOf("b"), plan.activated.map { it.entry.id })
    }
}
