package com.mistbell.tavern.v3.feature.chat

/**
 * Works out what a "regenerate this reply" request needs.
 *
 * The target assistant message stays in place; the caller rebuilds the prompt from the history
 * that precedes its user turn and, once the stream completes, appends the new text as another
 * variant instead of creating a new message.
 */
internal object RegeneratePlanner {

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
        val target = history.getOrNull(targetIndex)?.takeIf { it.role == "assistant" }
        val userIndex = target?.let { index ->
            (index - 1 downTo 0).firstOrNull { history[it].role == "user" }
        }
        val userText = userIndex?.let { history[it].content.trim() }
        return if (userIndex != null && !userText.isNullOrEmpty()) {
            Plan(
                prefixHistory = history.subList(0, userIndex).toList(),
                userText = userText,
                targetIndex = targetIndex,
            )
        } else {
            null
        }
    }
    }
}
