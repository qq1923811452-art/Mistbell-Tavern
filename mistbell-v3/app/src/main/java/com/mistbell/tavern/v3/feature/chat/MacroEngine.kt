package com.mistbell.tavern.v3.feature.chat

import kotlin.random.Random

/**
 * Minimal SillyTavern-style macro substitution at prompt boundaries.
 *
 * The card store and importer keep the raw text; callers resolve macros only when the text becomes
 * a prompt or an opening message, so editing a card never rewrites stored content.
 */
object MacroEngine {
    private const val MIN_DICE_COUNT = 1
    private const val MAX_DICE_COUNT = 20
    private const val MIN_DICE_SIDES = 2
    private const val MAX_DICE_SIDES = 1_000
    private val RANDOM_MACRO =
        Regex("\\{\\{random::(.*?)\\}\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ROLL_MACRO = Regex("\\{\\{roll::([^}]*)\\}\\}", RegexOption.IGNORE_CASE)
    private val ROLL_PATTERN = Regex("^(?:(\\d{1,2})?d)?(\\d{1,4})$", RegexOption.IGNORE_CASE)

    fun resolve(
        text: String,
        charName: String?,
        userName: String = "你",
        random: Random = Random.Default,
    ): String {
        if (text.isEmpty()) return text
        var resolved = resolveRolls(resolveRandom(text, random), random)
        resolved =
            replaceIgnoreCase(
                replaceIgnoreCase(resolved, "{{user}}", userName),
                "<user>",
                userName,
            )
        val name = charName?.trim()?.takeIf(String::isNotEmpty)
        if (name != null) {
            resolved =
                replaceIgnoreCase(
                    replaceIgnoreCase(resolved, "{{char}}", name),
                    "<char>",
                    name,
                )
        }
        return resolved
    }

    private fun resolveRandom(
        text: String,
        random: Random,
    ): String =
        RANDOM_MACRO.replace(text) { match ->
            val candidates =
                match.groupValues[1]
                    .split("::")
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            if (candidates.isEmpty()) match.value else candidates[random.nextInt(candidates.size)]
        }

    private fun resolveRolls(
        text: String,
        random: Random,
    ): String =
        ROLL_MACRO.replace(text) { match ->
            rollValue(match.groupValues[1], random) ?: match.value
        }

    private fun rollValue(
        spec: String,
        random: Random,
    ): String? =
        ROLL_PATTERN.matchEntire(spec.trim())?.let { match ->
            val count = match.groupValues[1].toIntOrNull() ?: MIN_DICE_COUNT
            val sides = match.groupValues[2].toIntOrNull()
            if (count in MIN_DICE_COUNT..MAX_DICE_COUNT &&
                sides != null &&
                sides in MIN_DICE_SIDES..MAX_DICE_SIDES
            ) {
                (1..count).sumOf { random.nextInt(sides) + 1 }.toString()
            } else {
                null
            }
        }

    private fun replaceIgnoreCase(
        text: String,
        marker: String,
        replacement: String,
    ): String =
        Regex(Regex.escape(marker), RegexOption.IGNORE_CASE)
            .replace(text, Regex.escapeReplacement(replacement))
}
