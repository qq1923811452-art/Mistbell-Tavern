package com.mistbell.tavern.v3.feature.chat

/** Tracks conversation scopes whose history is being deleted so new saves are rejected. */
internal class HistoryDeletionGuard {
    private val activeDeletes = mutableMapOf<String, Int>()

    @Synchronized
    fun begin(scopeId: String) {
        if (scopeId.isNotBlank()) {
            activeDeletes[scopeId] = (activeDeletes[scopeId] ?: 0) + 1
        }
    }

    @Synchronized
    fun end(scopeId: String) {
        val remaining = activeDeletes[scopeId] ?: return
        if (remaining <= 1) {
            activeDeletes.remove(scopeId)
        } else {
            activeDeletes[scopeId] = remaining - 1
        }
    }

    @Synchronized
    fun isDeleting(scopeId: String): Boolean = activeDeletes.containsKey(scopeId)
}
