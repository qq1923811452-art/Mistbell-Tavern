package com.mistbell.tavern.v3.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScopeFileNamesTest {
    @Test
    fun scopeFilesAreStableDistinctAndRemainSimpleFileNames() {
        val first = MemoryScopeFileNames.forScope("mistbell-v3-vectors.bin", "conversation-A")
        val same = MemoryScopeFileNames.forScope("mistbell-v3-vectors.bin", "conversation-A")
        val second = MemoryScopeFileNames.forScope("mistbell-v3-vectors.bin", "conversation-B")

        assertEquals(first, same)
        assertNotEquals(first, second)
        assertTrue('/' !in first && '\\' !in first)
        assertTrue(first.endsWith(".bin"))
    }

    @Test
    fun roleAndConversationBothPartitionMemoryScope() {
        val roleAConversation1 = MemoryScopeId.forRoleAndConversation("role-A", "conversation-1")
        val sameScope = MemoryScopeId.forRoleAndConversation("role-A", "conversation-1")
        val roleBConversation1 = MemoryScopeId.forRoleAndConversation("role-B", "conversation-1")
        val roleAConversation2 = MemoryScopeId.forRoleAndConversation("role-A", "conversation-2")

        assertEquals(roleAConversation1, sameScope)
        assertNotEquals(roleAConversation1, roleBConversation1)
        assertNotEquals(roleAConversation1, roleAConversation2)
    }

    @Test
    fun delimiterCharactersCannotAliasDifferentRoleConversationPairs() {
        val first = MemoryScopeId.forRoleAndConversation("role|part", "conversation")
        val second = MemoryScopeId.forRoleAndConversation("role", "part|conversation")

        assertNotEquals(first, second)
    }

    @Test
    fun roleScopeIsSharedByEveryConversationOfThatRole() {
        val shared = MemoryScopeId.forRole("role-A")
        val sameRoleConversation1 = MemoryScopeId.forRoleAndConversation("role-A", "conversation-1")
        val sameRoleConversation2 = MemoryScopeId.forRoleAndConversation("role-A", "conversation-2")

        assertEquals(shared, MemoryScopeId.forRole("role-A"))
        // The shared scope must never alias a per-conversation scope, or a migration could read
        // or overwrite another conversation's private files.
        assertNotEquals(shared, sameRoleConversation1)
        assertNotEquals(shared, sameRoleConversation2)
        assertNotEquals(MemoryScopeId.forRole("role-A"), MemoryScopeId.forRole("role-B"))
    }

    @Test
    fun roleScopeRejectsBlankRoles() {
        assertThrows(IllegalArgumentException::class.java) { MemoryScopeId.forRole(" ") }
    }
}
