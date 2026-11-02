package com.mistbell.tavern.v3.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.TemporalFactIdentity
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Copying a conversation scope into a role scope is one-way and must never damage the original.
 * It also rewrites the two record types that embed the scope id: a stale fact id would defeat
 * deduplication, and a stale override scope would stop manual edits from matching their fact.
 */
@RunWith(AndroidJUnit4::class)
class MemoryScopeMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val roleId = "role-migration-${UUID.randomUUID()}"

    @Test
    fun copiesTextAndFactsAndRewritesScopeBoundIdentities() {
        val roleScope = MemoryScopeId.forRole(roleId)
        val conversationScope = MemoryScopeId.forRoleAndConversation(roleId, "conversation-1")
        val textStore = textStore(conversationScope)
        val factStore = factStore(conversationScope)
        textStore.save(listOf(MemoryTextRecord("fact:1", "Mira left at midnight.")))
        val original = fact("fact:1", "turn-1", conversationScope, "Mira", "location", "harbour")
        factStore.save(listOf(original))

        val outcome = MemoryScopeMigration.copyConversationScopeToRole(context, roleScope, conversationScope)

        assertTrue(outcome.didCopy)
        assertFalse(outcome.failed)
        assertEquals(1, outcome.rewrittenFacts)

        val copied = factStore(roleScope).load()
        assertTrue(copied is TemporalFactLoadResult.Loaded)
        val fact = (copied as TemporalFactLoadResult.Loaded).facts.single()
        assertEquals(roleScope, fact.conversationId)
        assertEquals(
            TemporalFactIdentity.forObservation(roleScope, "turn-1", "Mira", "location", "harbour"),
            fact.id,
        )
        assertNotEquals(original.id, fact.id)

        val copiedText = textStore(roleScope).load()
        assertTrue(copiedText is MemoryTextLoadResult.Loaded)
        assertEquals(1, (copiedText as MemoryTextLoadResult.Loaded).records.size)

        // The source scope must still hold its untouched copy.
        val source = factStore(conversationScope).load()
        assertTrue(source is TemporalFactLoadResult.Loaded)
        assertEquals(original, (source as TemporalFactLoadResult.Loaded).facts.single())
    }

    @Test
    fun anExistingRoleScopeIsNeverOverwritten() {
        val roleScope = MemoryScopeId.forRole(roleId)
        val conversationScope = MemoryScopeId.forRoleAndConversation(roleId, "conversation-2")
        factStore(conversationScope).save(
            listOf(fact("fact-a", "turn-1", conversationScope, "Mira", "location", "harbour")),
        )
        val existing = fact("fact-b", "turn-9", roleScope, "Mira", "mood", "calm")
        factStore(roleScope).save(listOf(existing))

        val outcome = MemoryScopeMigration.copyConversationScopeToRole(context, roleScope, conversationScope)

        assertEquals(0, outcome.copiedFiles)
        assertFalse(outcome.didCopy)
        val after = factStore(roleScope).load()
        assertTrue(after is TemporalFactLoadResult.Loaded)
        assertEquals(listOf(existing), (after as TemporalFactLoadResult.Loaded).facts)
    }

    @Test
    fun nothingIsCopiedWhenTheConversationScopeIsEmpty() {
        val roleScope = MemoryScopeId.forRole(roleId)
        val conversationScope = MemoryScopeId.forRoleAndConversation(roleId, "conversation-3")

        val outcome = MemoryScopeMigration.copyConversationScopeToRole(context, roleScope, conversationScope)

        assertEquals(0, outcome.copiedFiles)
        assertFalse(outcome.didCopy)
        assertTrue(textStore(roleScope).load() is MemoryTextLoadResult.Missing)
    }

    private fun textStore(scope: String) = AtomicMemoryTextStore(
        context,
        MemoryScopeFileNames.forScope(AtomicMemoryTextStore.DEFAULT_FILE_NAME, scope),
    )

    private fun factStore(scope: String) = AtomicTemporalFactStore(
        context,
        MemoryScopeFileNames.forScope(AtomicTemporalFactStore.DEFAULT_FILE_NAME, scope),
    )

    private fun fact(
        id: String,
        turnId: String,
        conversationId: String,
        subject: String,
        predicate: String,
        value: String,
    ) = TemporalMemoryFact(
        id = id,
        conversationId = conversationId,
        sourceTurnId = turnId,
        subject = subject,
        predicate = predicate,
        value = value,
        observedAtEpochMillis = 1_000L,
        validFromEpochMillis = 1_000L,
    )
}
