package com.mistbell.tavern.v3.feature.worldbook

/** Deterministic placement planning, kept independent of persistence and UI. */
object WorldBookPlacement {
    const val POSITION_BEFORE_PROMPT = "before_prompt"
    const val POSITION_AFTER_PROMPT = "after_prompt"
    const val POSITION_AN_BEFORE = "an_before"
    const val POSITION_AN_AFTER = "an_after"
    const val POSITION_EM_BEFORE = "em_before"
    const val POSITION_EM_AFTER = "em_after"
    const val POSITION_DEPTH = "depth"

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
        val beforePrompt = mutableListOf<WorldBookEntry>()
        val afterPrompt = mutableListOf<WorldBookEntry>()
        val emBefore = mutableListOf<WorldBookEntry>()
        val emAfter = mutableListOf<WorldBookEntry>()
        val anBefore = mutableListOf<WorldBookEntry>()
        val anAfter = mutableListOf<WorldBookEntry>()
        val byDepth = mutableMapOf<Pair<Int, String>, MutableList<WorldBookEntry>>()

        val active = entries.filterNot(WorldBookEntry::disable)
        for (entry in active) {
            val isDepth = entry.insertPosition == POSITION_DEPTH || entry.depth > 0
            if (isDepth) {
                val key = entry.depth.coerceAtLeast(0) to normalizeDepthRole(entry.depthRole)
                byDepth.getOrPut(key) { mutableListOf() } += entry
            } else {
                when (entry.insertPosition) {
                    POSITION_AFTER_PROMPT -> afterPrompt += entry
                    POSITION_EM_BEFORE -> emBefore += entry
                    POSITION_EM_AFTER -> emAfter += entry
                    POSITION_AN_BEFORE -> anBefore += entry
                    POSITION_AN_AFTER -> anAfter += entry
                    else -> beforePrompt += entry
                }
            }
        }

        return Plan(
            beforePrompt = beforePrompt,
            afterPrompt = afterPrompt,
            emBefore = emBefore,
            emAfter = emAfter,
            anBefore = anBefore,
            anAfter = anAfter,
            byDepth = byDepth.toSortedMap(compareBy({ it.first }, { it.second })),
        )
    }

    private fun normalizeDepthRole(role: String): String = when (role) {
        DEPTH_ROLE_USER -> DEPTH_ROLE_USER
        DEPTH_ROLE_ASSISTANT -> DEPTH_ROLE_ASSISTANT
        else -> DEPTH_ROLE_SYSTEM
    }
}
