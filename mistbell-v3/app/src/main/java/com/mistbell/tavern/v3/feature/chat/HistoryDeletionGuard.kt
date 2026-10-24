package com.mistbell.tavern.v3.feature.chat

/** Tracks conversation scopes whose history is being deleted so new saves are rejected. */
internal class HistoryDeletionGuard {
    private val scopes = mutableSetOf<String>()

    @Synchronized
    fun begin(scopeId: String) {
        if (scopeId.isNotBlank()) scopes += scopeId
    }

    @Synchronized
    fun end(scopeId: String) {
        scopes -= scopeId
    }

    @Synchronized
    fun isDeleting(scopeId: String): Boolean = scopeId in scopes
}
