package com.mistbell.tavern.v3.feature.chat

/**
 * Pure helpers for swipeable assistant messages.
 *
 * A message owns an ordered list of alternatives and one active index. The chat transcript stores
 * the active text in `content` for compatibility; these helpers keep the alternatives coherent.
 */
object MessageVariants {

    fun append(existing: List<String>, alternative: String): List<String> {
        val trimmed = alternative.trim()
        val canAppend = trimmed.isNotEmpty() &&
            existing.size < PersistedChatMessage.MAX_VARIANTS
        return if (canAppend) existing + trimmed else existing
    }

    fun select(variants: List<String>, requested: Int): Int =
        if (variants.isEmpty()) 0 else requested.coerceIn(0, variants.lastIndex)

    fun next(variants: List<String>, current: Int): Int =
        if (variants.isEmpty()) 0 else (select(variants, current) + 1) % variants.size

    fun previous(variants: List<String>, current: Int): Int {
        if (variants.isEmpty()) return 0
        val size = variants.size
        return (select(variants, current) - 1 + size) % size
    }
}
