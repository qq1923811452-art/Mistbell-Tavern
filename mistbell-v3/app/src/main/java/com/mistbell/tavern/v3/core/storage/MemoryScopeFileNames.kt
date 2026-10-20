package com.mistbell.tavern.v3.core.storage

import java.security.MessageDigest
import java.util.UUID

/** Deterministic app-private file partitioning for each active conversation scope. */
object MemoryScopeFileNames {
    fun forScope(defaultFileName: String, scopeId: String): String {
        require(defaultFileName.isNotBlank() && defaultFileName != "." && defaultFileName != "..")
        require('/' !in defaultFileName && '\\' !in defaultFileName)
        require(scopeId.isNotBlank()) { "Memory scope ID must not be blank." }
        val suffix = MessageDigest.getInstance("SHA-256")
            .digest(scopeId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
        val extension = defaultFileName.substringAfterLast('.', "")
        val base = if (extension.isEmpty()) defaultFileName else defaultFileName.dropLast(extension.length + 1)
        return if (extension.isEmpty()) "$base-$suffix" else "$base-$suffix.$extension"
    }
}

/** Stable role-and-conversation identity; either dimension changing creates an isolated scope. */
object MemoryScopeId {
    fun forRoleAndConversation(roleId: String, conversationId: String): String {
        require(roleId.isNotBlank()) { "Character/role ID must not be blank." }
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank." }
        // Length-prefix both values so delimiter characters inside either ID cannot alias a
        // different role/conversation pair and accidentally share private memory files.
        val identity = "${roleId.length}:$roleId${conversationId.length}:$conversationId"
        return UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
    }
}
