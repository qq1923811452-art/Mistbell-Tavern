package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.TemporalFactLedger
import com.mistbell.tavern.v3.core.memory.TemporalFactObservation
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalFactTextSynchronizerTest {
    @Test
    fun syncProjectsOnlyCurrentFactsAndPreservesUnrelatedMemories() {
        val facts =
            TemporalFactLedger.apply(
                emptyList(),
                listOf(
                    observation(turn = "turn-1", value = "tea", at = 100L),
                    observation(turn = "turn-2", value = "coffee", at = 200L),
                ),
            )
        val oldFact = facts.single { it.value == "tea" }
        val currentFact = facts.single { it.value == "coffee" }
        val unrelated = MemoryTextRecord(id = "note:manual", text = "A manually saved memory.")
        val textStore =
            FakeMemoryTextStore(
                listOf(
                    MemoryTextRecord(
                        id = TemporalFactTextSynchronizer.FACT_TEXT_ID_PREFIX + oldFact.id,
                        text = "stale fact",
                    ),
                    unrelated,
                ),
            )
        val factStore = FakeTemporalFactStore(TemporalFactLoadResult.Loaded(facts))
        val synchronizer = TemporalFactTextSynchronizer(factStore, textStore)

        val first = synchronizer.synchronize()

        assertTrue(first is TemporalFactTextSyncResult.Synchronized)
        first as TemporalFactTextSyncResult.Synchronized
        assertTrue(first.changed)
        assertEquals(1, first.currentFacts)
        assertEquals(
            setOf(
                unrelated.id,
                TemporalFactTextSynchronizer.FACT_TEXT_ID_PREFIX + currentFact.id,
            ),
            textStore.records.map(MemoryTextRecord::id).toSet(),
        )
        assertEquals("A manually saved memory.", textStore.records.single { it.id == unrelated.id }.text)

        val second = synchronizer.synchronize()
        assertTrue(second is TemporalFactTextSyncResult.Synchronized)
        assertFalse((second as TemporalFactTextSyncResult.Synchronized).changed)
    }

    @Test
    fun missingFactLedgerRemovesStaleFactProjectionButKeepsManualMemories() {
        val staleFact = MemoryTextRecord(id = "fact:old-ledger-fact", text = "Stale fact.")
        val unrelated = MemoryTextRecord(id = "note:manual", text = "Keep me.")
        val textStore = FakeMemoryTextStore(listOf(staleFact, unrelated))
        val synchronizer =
            TemporalFactTextSynchronizer(
                FakeTemporalFactStore(TemporalFactLoadResult.Missing),
                textStore,
            )

        val result = synchronizer.synchronize()

        assertTrue(result is TemporalFactTextSyncResult.Synchronized)
        assertTrue((result as TemporalFactTextSyncResult.Synchronized).changed)
        assertEquals(listOf(unrelated), textStore.records)
    }

    @Test
    fun corruptFactLedgerDoesNotTouchExistingMemoryTexts() {
        val existing = MemoryTextRecord(id = "note:manual", text = "Keep me.")
        val textStore = FakeMemoryTextStore(listOf(existing))
        val synchronizer =
            TemporalFactTextSynchronizer(
                FakeTemporalFactStore(TemporalFactLoadResult.Corrupt("corrupt")),
                textStore,
            )

        val result = synchronizer.synchronize()

        assertTrue(result is TemporalFactTextSyncResult.Skipped)
        assertEquals(listOf(existing), textStore.records)
    }

    @Test
    fun projectionReadsLatestOverridesInsideTheSharedTransaction() {
        val facts = TemporalFactLedger.apply(emptyList(), listOf(observation("turn-1", "tea", 1L)))
        val fact = facts.single()
        val textStore = FakeMemoryTextStore(emptyList())
        var insideTransaction = false
        val overrides =
            object : com.mistbell.tavern.v3.core.storage.MemoryOverridePersistence {
                override fun load(): com.mistbell.tavern.v3.core.storage.MemoryOverrideLoadResult =
                    error("Projection must not read an unlocked override snapshot")

                override fun update(
                    transform: (List<com.mistbell.tavern.v3.core.memory.MemoryOverride>) ->
                    List<com.mistbell.tavern.v3.core.memory.MemoryOverride>,
                ): List<com.mistbell.tavern.v3.core.memory.MemoryOverride> {
                    insideTransaction = true
                    return try {
                        val latest =
                            listOf(
                                com.mistbell.tavern.v3.core.memory.MemoryOverride(
                                    fact.conversationId,
                                    fact.id,
                                    fact.subject,
                                    fact.predicate,
                                    "coffee",
                                    2L,
                                    2L,
                                ),
                            )
                        assertEquals(latest, transform(latest))
                        latest
                    } finally {
                        insideTransaction = false
                    }
                }
            }
        val factStore =
            object : TemporalFactPersistence {
                override fun load(): TemporalFactLoadResult {
                    assertTrue(insideTransaction)
                    return TemporalFactLoadResult.Loaded(facts)
                }

                override fun save(facts: List<TemporalMemoryFact>) = error("ledger is read-only")
            }
        val result = TemporalFactTextSynchronizer(factStore, textStore, overrides).synchronize()
        assertTrue(result is TemporalFactTextSyncResult.Synchronized)
        assertEquals("user favorite drink: coffee", textStore.records.single().text)
    }

    private fun observation(
        turn: String,
        value: String,
        at: Long,
    ) = TemporalFactObservation(
        conversationId = "conversation-1",
        sourceTurnId = turn,
        subject = "user",
        predicate = "favorite drink",
        value = value,
        observedAtEpochMillis = at,
    )

    private class FakeTemporalFactStore(
        private var result: TemporalFactLoadResult,
    ) : TemporalFactPersistence {
        override fun load(): TemporalFactLoadResult = result

        override fun save(facts: List<TemporalMemoryFact>) {
            result = TemporalFactLoadResult.Loaded(facts)
        }
    }

    private class FakeMemoryTextStore(
        initial: List<MemoryTextRecord>,
    ) : MemoryTextPersistence {
        var records: List<MemoryTextRecord> = initial
            private set

        override fun loadMemoryTexts(): List<MemoryTextRecord> = records

        override fun save(records: List<MemoryTextRecord>) {
            this.records = records
        }

        override fun load(): MemoryTextLoadResult = MemoryTextLoadResult.Loaded(records)

        override fun update(transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>): List<MemoryTextRecord> {
            val updated = transform(records)
            if (updated != records) records = updated
            return records
        }
    }
}
