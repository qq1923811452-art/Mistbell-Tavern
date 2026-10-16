package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.TemporalFactLedger
import com.mistbell.tavern.v3.core.memory.TemporalFactObservation
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalFactTextSynchronizerTest {
    @Test
    fun syncProjectsOnlyCurrentFactsAndPreservesUnrelatedMemories() {
        val facts = TemporalFactLedger.apply(
            emptyList(),
            listOf(
                observation(turn = "turn-1", value = "tea", at = 100L),
                observation(turn = "turn-2", value = "coffee", at = 200L),
            ),
        )
        val oldFact = facts.single { it.value == "tea" }
        val currentFact = facts.single { it.value == "coffee" }
        val unrelated = MemoryTextRecord(id = "note:manual", text = "A manually saved memory.")
        val textStore = FakeMemoryTextStore(
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
    fun corruptFactLedgerDoesNotTouchExistingMemoryTexts() {
        val existing = MemoryTextRecord(id = "note:manual", text = "Keep me.")
        val textStore = FakeMemoryTextStore(listOf(existing))
        val synchronizer = TemporalFactTextSynchronizer(
            FakeTemporalFactStore(TemporalFactLoadResult.Corrupt("corrupt")),
            textStore,
        )

        val result = synchronizer.synchronize()

        assertTrue(result is TemporalFactTextSyncResult.Skipped)
        assertEquals(listOf(existing), textStore.records)
    }

    private fun observation(turn: String, value: String, at: Long) = TemporalFactObservation(
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
