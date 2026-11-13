package com.mistbell.tavern.v3.feature.chat

/**
 * Minimal SillyTavern-style macro substitution at prompt boundaries.
 *
 * The card store and importer keep the raw text; callers resolve macros only when the text becomes
 * a prompt or an opening message, so editing a card never rewrites stored content.
 */
object MacroEngine {
    fun resolve(
        text: String,
        charName: String?,
        userName: String = "你",
    ): String {
        if (text.isEmpty()) return text
        var resolved =
            replaceIgnoreCase(
                replaceIgnoreCase(text, "{{user}}", userName),
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

    private fun replaceIgnoreCase(
        text: String,
        marker: String,
        replacement: String,
    ): String =
        Regex(Regex.escape(marker), RegexOption.IGNORE_CASE)
            .replace(text, Regex.escapeReplacement(replacement))
}
