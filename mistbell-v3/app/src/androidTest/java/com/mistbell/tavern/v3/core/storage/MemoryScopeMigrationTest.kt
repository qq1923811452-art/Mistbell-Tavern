package com.mistbell.tavern.v3.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.memory.MemoryProjection
import com.mistbell.tavern.v3.core.memory.MemoryTextCodec
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.TemporalFactCodec
import com.mistbell.tavern.v3.core.memory.TemporalFactLedger
import com.mistbell.tavern.v3.core.memory.TemporalFactObservation
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
            TemporalFactLedger.stableId(TemporalFactObservation(roleScope, "turn-1", "Mira", "location", "harbour", 1_000L)),
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

    @Test
    fun rewritesManualReferencesIncludingLegacyAndHistoricalOverrides() {
        val source = MemoryScopeId.forRoleAndConversation(roleId, "edits")
        val target = MemoryScopeId.forRole(roleId)
        val historical =
            fact("old", "t0", source, "Mira", "location", "home")
                .copy(validUntilEpochMillis = 1_000L)
        val current = fact("current", "t1", source, "Mira", "location", "harbour")
        factStore(source).save(listOf(historical, current))
        overrideStore(source).update {
            listOf(
                MemoryOverride(source, "old", "Edited person", "edited relation", "old manual", 1L, 1L),
                MemoryOverride(source, "current", "Edited person", "edited relation", "manual", 2L, 2L),
            )
        }
        assertFalse(MemoryScopeMigration.copyConversationScopeToRole(context, target, source).failed)
        val facts = (factStore(target).load() as TemporalFactLoadResult.Loaded).facts
        val edits = (overrideStore(target).load() as MemoryOverrideLoadResult.Loaded).overrides
        assertTrue(edits.all { edit -> facts.any { it.id == edit.factId } })
        val resolved = EffectiveMemoryResolver.resolve(facts, edits)
        assertEquals("manual", resolved.first { it.isCurrent }.value)
        assertEquals("old manual", resolved.first { !it.isCurrent }.value)
        val texts = (textStore(target).load() as MemoryTextLoadResult.Loaded).records
        assertEquals(MemoryProjection.factRecordId(facts.first { it.isCurrent }.id), texts.single().id)
    }

    @Test
    fun replayOfMigratedHistoricalObservationDoesNotResurrectOldValue() {
        val source = MemoryScopeId.forRoleAndConversation(roleId, "replay")
        val target = MemoryScopeId.forRole(roleId)
        val old =
            fact("old", "t0", source, "Mira", "location", "home")
                .copy(validUntilEpochMillis = 2_000L)
        val current =
            fact("new", "t1", source, "Mira", "location", "harbour")
                .copy(observedAtEpochMillis = 2_000L, validFromEpochMillis = 2_000L)
        factStore(source).save(listOf(old, current))
        MemoryScopeMigration.copyConversationScopeToRole(context, target, source)
        val migrated = (factStore(target).load() as TemporalFactLoadResult.Loaded).facts
        val replay =
            TemporalFactLedger.apply(
                migrated,
                listOf(
                    TemporalFactObservation(target, "t0", "Mira", "location", "home", 9_000L),
                ),
            )
        assertEquals(migrated, replay)
    }

    @Test
    fun existingRoleTextOrBackupPreventsCrossConversationMixing() {
        val source = MemoryScopeId.forRoleAndConversation(roleId, "other-conversation")
        val target = MemoryScopeId.forRole(roleId)
        factStore(source).save(listOf(fact("other", "t1", source, "Mira", "location", "harbour")))
        textStore(target).save(listOf(MemoryTextRecord("custom", "authoritative role memory")))
        val textFile = File(context.filesDir, MemoryScopeFileNames.forScope(AtomicMemoryTextStore.DEFAULT_FILE_NAME, target))
        assertTrue(textFile.renameTo(File(textFile.path + ".bak")))
        assertFalse(MemoryScopeMigration.copyConversationScopeToRole(context, target, source).didCopy)
        assertTrue(factStore(target).load() is TemporalFactLoadResult.Missing)
    }

    @Test
    fun backupOnlySourceIsCopiedWithoutModifyingOriginalFiles() {
        val source = MemoryScopeId.forRoleAndConversation(roleId, "backup-only")
        val target = MemoryScopeId.forRole(roleId)
        factStore(source).save(listOf(fact("original", "t1", source, "Mira", "location", "home")))
        val base = File(context.filesDir, MemoryScopeFileNames.forScope(AtomicTemporalFactStore.DEFAULT_FILE_NAME, source))
        val backup = File(base.path + ".bak")
        assertTrue(base.renameTo(backup))
        val before = backup.readBytes()
        assertTrue(MemoryScopeMigration.copyConversationScopeToRole(context, target, source).didCopy)
        assertFalse(base.exists())
        assertTrue(before.contentEquals(backup.readBytes()))
        assertTrue(factStore(target).load() is TemporalFactLoadResult.Loaded)
    }

    @Test
    fun corruptSourcePublishesNothingAndCanRetryAfterRepair() {
        val source = MemoryScopeId.forRoleAndConversation(roleId, "corrupt")
        val target = MemoryScopeId.forRole(roleId)
        textStore(source).save(listOf(MemoryTextRecord("custom", "preserved")))
        val base = File(context.filesDir, MemoryScopeFileNames.forScope(AtomicTemporalFactStore.DEFAULT_FILE_NAME, source))
        base.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(MemoryScopeMigration.copyConversationScopeToRole(context, target, source).failed)
        assertTrue(textStore(target).load() is MemoryTextLoadResult.Missing)
        factStore(source).save(listOf(fact("fixed", "t1", source, "Mira", "location", "home")))
        assertTrue(MemoryScopeMigration.copyConversationScopeToRole(context, target, source).didCopy)
    }

    @Test
    fun interruptedPublicationIsRecoveredBeforeReadAndConcurrentWrite() {
        val target = MemoryScopeId.forRole(roleId)
        val textName = MemoryScopeFileNames.forScope(AtomicMemoryTextStore.DEFAULT_FILE_NAME, target)
        val factName = MemoryScopeFileNames.forScope(AtomicTemporalFactStore.DEFAULT_FILE_NAME, target)
        val original = fact("original", "t1", target, "Mira", "location", "home")
        val files =
            linkedMapOf(
                textName to MemoryTextCodec.encode(listOf(MemoryTextRecord("custom", "preserved"))),
                factName to TemporalFactCodec.encode(listOf(original)),
            )
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val publisher =
            thread {
                MemoryScopeStorageLock.withLock(File(context.filesDir, "test-migration")) {
                    runCatching {
                        MemoryScopeStorageLock.publish(context.filesDir, files) {
                            blocked.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            throw IOException("injected interruption after first target")
                        }
                    }
                }
            }
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        val writer =
            thread {
                factStore(target).update { it + fact("later", "t2", target, "Mira", "mood", "calm") }
                completed.countDown()
            }
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        publisher.join(5_000L)
        writer.join(5_000L)
        assertEquals(0L, completed.count)
        assertEquals(setOf("original", "later"), (factStore(target).load() as TemporalFactLoadResult.Loaded).facts.map { it.id }.toSet())
        assertEquals("preserved", (textStore(target).load() as MemoryTextLoadResult.Loaded).records.single().text)
    }

    @Test
    fun migratedVectorIdsStayAlignedWithRewrittenFactTexts() {
        val source = MemoryScopeId.forRoleAndConversation(roleId, "vectors")
        val target = MemoryScopeId.forRole(roleId)
        val original = fact("old-id", "t1", source, "Mira", "location", "home")
        factStore(source).save(listOf(original))
        val text = MemoryTextRecord(MemoryProjection.factRecordId(original.id), "Mira location: home")
        textStore(source).save(listOf(text))
        AtomicVectorSnapshotStore(context, MemoryScopeFileNames.forScope(AtomicVectorSnapshotStore.DEFAULT_FILE_NAME, source))
            .save(
                com.mistbell.tavern.v3.core.memory.VectorSnapshot(
                    "model",
                    1,
                    listOf(
                        com.mistbell.tavern.v3.core.memory.StoredVector(text.id, text.sourceTextSha256, 1L, listOf(1f)),
                    ),
                ),
            )
        assertTrue(MemoryScopeMigration.copyConversationScopeToRole(context, target, source).didCopy)
        val texts = (textStore(target).load() as MemoryTextLoadResult.Loaded).records
        val vectors =
            AtomicVectorSnapshotStore(context, MemoryScopeFileNames.forScope(AtomicVectorSnapshotStore.DEFAULT_FILE_NAME, target))
                .load("model", 1) as VectorSnapshotLoadResult.Loaded
        assertEquals(texts.single().id, vectors.snapshot.records.single().id)
        assertEquals(texts.single().sourceTextSha256, vectors.snapshot.records.single().sourceTextSha256)
    }

    private fun overrideStore(scope: String) =
        AtomicMemoryOverrideStore(
            context,
            MemoryScopeFileNames.forScope(AtomicMemoryOverrideStore.DEFAULT_FILE_NAME, scope),
        )

    private fun textStore(scope: String) =
        AtomicMemoryTextStore(
            context,
            MemoryScopeFileNames.forScope(AtomicMemoryTextStore.DEFAULT_FILE_NAME, scope),
        )

    private fun factStore(scope: String) =
        AtomicTemporalFactStore(
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
