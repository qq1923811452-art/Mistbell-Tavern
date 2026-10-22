package com.mistbell.tavern.v3.feature.chat

/**
 * Works out what a "regenerate this reply" request needs.
 *
 * The target assistant message stays in place; the caller rebuilds the prompt from the history
 * that precedes its user turn and, once the stream completes, appends the new text as another
 * variant instead of creating a new message.
 */
object RegeneratePlanner {

    data class Plan(
        /** History before the user turn that produced the target reply. */
        val prefixHistory: List<ChatLine>,
        /** User text to send again. */
        val userText: String,
        /** Index of the assistant line that will receive the new variant. */
        val targetIndex: Int,
    )

    fun plan(history: List<ChatLine>, targetMessageId: String): Plan? {
        val targetIndex = history.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val target = history[targetIndex]
        if (target.role != "assistant") return null
        val userIndex = (targetIndex - 1 downTo 0).firstOrNull { history[it].role == "user" } ?: return null
        val userText = history[userIndex].content.trim()
        if (userText.isEmpty()) return null
        return Plan(
            prefixHistory = history.subList(0, userIndex).toList(),
            userText = userText,
            targetIndex = targetIndex,
        )
    }
}
