package com.mistbell.tavern.v3.feature.worldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookPlacementTest {
    private fun entry(id: String, position: String = WorldBookPlacement.POSITION_BEFORE_PROMPT,
                      depth: Int = 0, role: String = "system", disabled: Boolean = false) =
        WorldBookEntry(id, "book", content = id, insertPosition = position, depth = depth,
            depthRole = role, disable = disabled)

    @Test fun anchoredEntriesRespectPlacementAndDisabledEntriesAreIgnored() {
        val plan = WorldBookPlacement.plan(listOf(
            entry("before"), entry("after", WorldBookPlacement.POSITION_AFTER_PROMPT),
            entry("disabled", disabled = true),
        ))
        assertEquals(listOf("before"), plan.beforePrompt.map { it.id })
        assertEquals(listOf("after"), plan.afterPrompt.map { it.id })
    }

    @Test fun depthEntriesAreGroupedByDepthAndRole() {
        val plan = WorldBookPlacement.plan(listOf(
            entry("deep-a", depth = 2), entry("deep-b", depth = 2),
            entry("user", depth = 1, role = WorldBookPlacement.DEPTH_ROLE_USER),
        ))
        assertEquals(listOf("user"), plan.byDepth[1 to "user"]?.map { it.id })
        assertEquals(listOf("deep-a", "deep-b"), plan.byDepth[2 to "system"]?.map { it.id })
        assertTrue(plan.beforePrompt.isEmpty())
    }
}
