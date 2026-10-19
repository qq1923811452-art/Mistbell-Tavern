package com.mistbell.tavern.v3.feature.worldbook

/** Deterministic placement planning, kept independent of persistence and UI. */
object WorldBookPlacement {
    const val POSITION_BEFORE_PROMPT = "before_prompt"
    const val POSITION_DEPTH = "depth"
    const val POSITION_AFTER_PROMPT = "after_prompt"
    const val POSITION_AN_BEFORE = "an_before"
    const val POSITION_AN_AFTER = "an_after"
    const val POSITION_EM_BEFORE = "em_before"
    const val POSITION_EM_AFTER = "em_after"

    const val DEPTH_ROLE_SYSTEM = "system"
    const val DEPTH_ROLE_USER = "user"
    const val DEPTH_ROLE_ASSISTANT = "assistant"

    data class Plan(
        val beforePrompt: List<WorldBookEntry>,
        val afterPrompt: List<WorldBookEntry>,
        val emBefore: List<WorldBookEntry>,
        val emAfter: List<WorldBookEntry>,
        val anBefore: List<WorldBookEntry>,
        val anAfter: List<WorldBookEntry>,
        val byDepth: Map<Pair<Int, String>, List<WorldBookEntry>>,
    )

    fun plan(entries: List<WorldBookEntry>): Plan {
        val active = entries.filterNot { it.disable }
        val anchored = active.filter { it.insertPosition != POSITION_DEPTH && it.depth <= 0 }
        return Plan(
            beforePrompt = anchored.filter { it.insertPosition == POSITION_BEFORE_PROMPT },
            afterPrompt = anchored.filter { it.insertPosition == POSITION_AFTER_PROMPT },
            emBefore = anchored.filter { it.insertPosition == POSITION_EM_BEFORE },
            emAfter = anchored.filter { it.insertPosition == POSITION_EM_AFTER },
            anBefore = anchored.filter { it.insertPosition == POSITION_AN_BEFORE },
            anAfter = anchored.filter { it.insertPosition == POSITION_AN_AFTER },
            byDepth = active.filter { it.insertPosition == POSITION_DEPTH || it.depth > 0 }
                .groupBy { it.depth.coerceIn(0, 10) to normalizeDepthRole(it.depthRole) }
                .toSortedMap(compareBy({ it.first }, { it.second })),
        )
    }

    private fun normalizeDepthRole(role: String): String = when (role) {
        DEPTH_ROLE_USER -> DEPTH_ROLE_USER
        DEPTH_ROLE_ASSISTANT -> DEPTH_ROLE_ASSISTANT
        else -> DEPTH_ROLE_SYSTEM
    }
}
