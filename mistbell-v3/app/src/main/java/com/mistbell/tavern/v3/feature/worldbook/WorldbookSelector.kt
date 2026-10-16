package com.mistbell.tavern.v3.feature.worldbook

/**
 * Deterministic activation policy. Constant entries are always included when enabled,
 * regardless of whether they have keywords. Recursive expansion is bounded and cycle-safe.
 */
object WorldbookSelector {
    fun activate(
        document: WorldbookDocument,
        text: String,
        maxRecursiveDepth: Int = 4,
    ): List<WorldbookEntry> {
        require(maxRecursiveDepth >= 0) { "Maximum recursive depth cannot be negative." }
        val enabled = document.entries.filter(WorldbookEntry::enabled)
        val selectedIds = linkedSetOf<String>()
        val depthById = mutableMapOf<String, Int>()

        enabled.filter(WorldbookEntry::constant).forEach {
            selectedIds += it.id
            depthById[it.id] = 0
        }

        var scanText = text
        var depth = 0
        while (true) {
            val matches = enabled.filter { entry ->
                entry.id !in selectedIds &&
                    entry.keys.isNotEmpty() &&
                    containsAny(scanText, entry.keys) &&
                    (!entry.requireSecondaryMatch ||
                        (entry.secondaryKeys.isNotEmpty() && containsAny(scanText, entry.secondaryKeys)))
            }
            if (matches.isEmpty()) break
            matches.forEach { entry ->
                selectedIds += entry.id
                depthById[entry.id] = depth + 1
            }
            if (depth >= maxRecursiveDepth) break
            val recursiveContent = matches
                .filter(WorldbookEntry::recursive)
                .joinToString("\n") { it.content }
            if (recursiveContent.isBlank()) break
            scanText += "\n" + recursiveContent
            depth += 1
        }

        val position = document.entries.mapIndexed { index, entry -> entry.id to index }.toMap()
        return enabled.filter { it.id in selectedIds }.sortedWith(
            compareByDescending<WorldbookEntry> { it.constant }
                .thenByDescending(WorldbookEntry::priority)
                .thenBy(WorldbookEntry::order)
                .thenBy { position[it.id] ?: Int.MAX_VALUE },
        )
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        val haystack = text.lowercase()
        return keywords.any { keyword -> keyword.lowercase() in haystack }
    }
}
