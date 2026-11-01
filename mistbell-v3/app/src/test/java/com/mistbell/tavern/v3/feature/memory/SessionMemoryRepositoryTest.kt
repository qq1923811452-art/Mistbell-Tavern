package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import com.mistbell.tavern.v3.core.storage.MemoryOverrideLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryOverridePersistence
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.MemoryTextPersistence
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryRepositoryTest {
    private val facts = mutableListOf(fact("f1", "职业", "法师"), fact("f2", "居所", "塔"))
    private val texts = mutableListOf(MemoryTextRecord("fact:f1", "艾琳 职业: 法师"))
    private val overrides = mutableListOf<com.mistbell.tavern.v3.core.memory.MemoryOverride>()
    private var textWriteFails = false

    @Suppress("ktlint:standard:function-signature") // Keep signatures within Detekt line length.
    private val repository =
        SessionMemoryRepository(
            factStore =
                object : TemporalFactPersistence {
                    override fun save(facts: List<TemporalMemoryFact>) {
                        this@SessionMemoryRepositoryTest.facts.clear()
                        this@SessionMemoryRepositoryTest.facts += facts
                    }

                    override fun load(): TemporalFactLoadResult = TemporalFactLoadResult.Loaded(facts.toList())
                },
            memoryTextStore =
                object : MemoryTextPersistence {
                    override fun save(records: List<MemoryTextRecord>) {
                        texts.clear()
                        texts += records
                    }

                    override fun load(): MemoryTextLoadResult = MemoryTextLoadResult.Loaded(texts.toList())

                    override fun update(
                        transform: (List<MemoryTextRecord>) -> List<MemoryTextRecord>,
                    ): List<MemoryTextRecord> {
                        check(!textWriteFails) { "projection failure" }
                        val updated = transform(texts.toList())
                        texts.clear()
                        texts += updated
                        return updated
                    }

                    override fun loadMemoryTexts(): List<MemoryTextRecord> = texts.toList()
                },
            overrideStore =
                object : MemoryOverridePersistence {
                    override fun load(): MemoryOverrideLoadResult = MemoryOverrideLoadResult.Loaded(overrides.toList())

                    override fun update(
                        transform: (List<com.mistbell.tavern.v3.core.memory.MemoryOverride>) ->
                        List<com.mistbell.tavern.v3.core.memory.MemoryOverride>,
                    ): List<com.mistbell.tavern.v3.core.memory.MemoryOverride> {
                        val updated = transform(overrides.toList())
                        overrides.clear()
                        overrides += updated
                        return updated
                    }
                },
            clockMillis = { 100L },
        )

    @Test
    fun editIsPersistedAndTheSearchableTextFollows() {
        val result = repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("艾琳", "职业", "剑士"))
        assertTrue(result is SessionMemorySaveResult.Saved)
        assertEquals(1, overrides.size)
        assertEquals("艾琳 职业: 剑士", texts.first { it.id == "fact:f1" }.text)
        // The projection rewrites every current fact, so unrelated ones keep their automatic text.
        assertEquals("艾琳 居所: 塔", texts.first { it.id == "fact:f2" }.text)
        assertEquals(2, texts.size)
    }

    @Test
    fun staleRevisionIsRejectedInsteadOfOverwriting() {
        repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("艾琳", "职业", "剑士"))
        val second = repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("艾琳", "职业", "盗贼"))
        assertTrue(second is SessionMemorySaveResult.Conflict)
        assertEquals(1L, (second as SessionMemorySaveResult.Conflict).currentRevision)
        assertEquals("剑士", overrides.single().value)
    }

    @Test
    fun revertRestoresTheAutomaticValue() {
        repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("艾琳", "职业", "剑士"))
        val reverted = repository.revert("f1", 1L)
        assertTrue(reverted is SessionMemorySaveResult.Saved)
        assertTrue(overrides.single().reverted)
        assertEquals(2L, overrides.single().revision)
        assertEquals("艾琳 职业: 法师", texts.first { it.id == "fact:f1" }.text)
    }

    @Test
    fun blankFieldsAreRejectedBeforeAnyWrite() {
        val result = repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("  ", "职业", "剑士"))
        assertTrue(result is SessionMemorySaveResult.Invalid)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun unknownFactIsNotFound() {
        val result = repository.saveEdit(SCOPE, "missing", 0L, MemoryEditDraft("a", "b", "c"))
        assertTrue(result is SessionMemorySaveResult.NotFound)
    }

    @Test
    fun reExtractedFactCanBeEditedAndRevertedWithItsDisplayedRevision() {
        repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("贝拉", "职务", "剑士"))
        facts[0] = facts[0].copy(validUntilEpochMillis = 2L)
        facts += fact("f9", "职业", "盗贼")
        val loaded = repository.load(SCOPE) as SessionMemoryLoadResult.Loaded
        val latest = loaded.entries.first { it.factId == "f9" }
        assertEquals("贝拉", latest.subject)
        assertEquals("剑士", latest.value)
        val edited = repository.saveEdit(SCOPE, "f9", latest.revision, MemoryEditDraft("贝拉", "职务", "骑士"))
        assertTrue(edited is SessionMemorySaveResult.Saved)
        val reverted = repository.revert("f9", (edited as SessionMemorySaveResult.Saved).revision)
        assertTrue(reverted is SessionMemorySaveResult.Saved)
        assertEquals("盗贼", (reverted as SessionMemorySaveResult.Saved).entry.value)
        assertEquals("艾琳 职业: 盗贼", texts.first { it.id == "fact:f9" }.text)
    }

    @Test
    fun resetAndReEditDoNotReuseAnOldRevision() {
        repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("艾琳", "职业", "剑士"))
        val reset = repository.revert("f1", 1L) as SessionMemorySaveResult.Saved
        assertEquals(2L, reset.entry.revision)
        repository.saveEdit(SCOPE, "f1", reset.revision, MemoryEditDraft("艾琳", "职业", "骑士"))
        val stale = repository.saveEdit(SCOPE, "f1", 1L, MemoryEditDraft("艾琳", "职业", "旧草稿"))
        assertTrue(stale is SessionMemorySaveResult.Conflict)
        assertEquals("骑士", overrides.single().value)
    }

    @Test
    fun scopeMismatchCannotWriteOtherConversationsFacts() {
        val result = repository.saveEdit("other", "f1", 0L, MemoryEditDraft("a", "b", "c"))
        assertTrue(result is SessionMemorySaveResult.NotFound)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun committedEditReportsProjectionFailureWithoutLosingTheRevision() {
        textWriteFails = true
        val result = repository.saveEdit(SCOPE, "f1", 0L, MemoryEditDraft("艾琳", "职业", "剑士"))
        assertTrue(result is SessionMemorySaveResult.Saved)
        assertTrue((result as SessionMemorySaveResult.Saved).projectionWarning != null)
        assertEquals("剑士", overrides.single().value)
        assertEquals("艾琳 职业: 法师", texts.single().text)
    }

    private fun fact(
        id: String,
        predicate: String,
        value: String,
    ) = TemporalMemoryFact(
        id = id,
        conversationId = SCOPE,
        sourceTurnId = "turn-1",
        subject = "艾琳",
        predicate = predicate,
        value = value,
        observedAtEpochMillis = 1L,
        validFromEpochMillis = 1L,
    )

    private companion object {
        const val SCOPE = "scope"
    }
}
