package com.mistbell.tavern.v3.feature.memory

import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryOrigin
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveMemoryResolverTest {
    @Test
    fun manualRevisionWinsOverTheAutomaticFact() {
        val fact = fact("f1", "艾琳", "职业", "法师")
        val override = MemoryOverride(SCOPE, "f1", "艾琳", "职业", "剑士", revision = 2L, updatedAtEpochMillis = 5L)
        val resolved = EffectiveMemoryResolver.resolve(listOf(fact), listOf(override)).single()
        assertEquals("剑士", resolved.value)
        assertEquals(MemoryOrigin.MANUAL, resolved.origin)
        assertEquals(2L, resolved.revision)
        assertEquals("艾琳 职业: 剑士", resolved.displayText)
    }

    @Test
    fun revisionSurvivesAFactReExtractedUnderANewId() {
        val reExtracted = fact("f9", "艾琳", "职业", "法师")
        val override = MemoryOverride(SCOPE, "f1", "艾琳", "职业", "剑士", revision = 1L, updatedAtEpochMillis = 1L)
        val resolved = EffectiveMemoryResolver.resolve(listOf(reExtracted), listOf(override)).single()
        assertEquals("剑士", resolved.value)
        assertEquals(MemoryOrigin.MANUAL, resolved.origin)
    }

    @Test
    fun unrelatedFactsStayAutomaticAndHistoryIsKept() {
        val current = fact("f1", "艾琳", "职业", "法师")
        val old = fact("f0", "艾琳", "职业", "学徒").copy(validUntilEpochMillis = 10L)
        val resolved = EffectiveMemoryResolver.resolve(listOf(current, old), emptyList())
        assertEquals(2, resolved.size)
        assertTrue(resolved.first().isCurrent)
        assertEquals(MemoryOrigin.AUTOMATIC, resolved.first().origin)
        val texts = EffectiveMemoryResolver.currentTexts(listOf(current, old), emptyList())
        assertEquals(1, texts.size)
        assertEquals("艾琳 职业: 法师", texts.single().text)
    }

    @Test
    fun currentOverrideDoesNotRewriteAnUneditedHistoricalFact() {
        val old = fact("f0", "艾琳", "职业", "学徒").copy(validUntilEpochMillis = 10L)
        val current = fact("f1", "艾琳", "职业", "法师")
        val override = MemoryOverride(SCOPE, "f1", "艾琳", "职业", "剑士", 1L, 5L)
        val entries = EffectiveMemoryResolver.resolve(listOf(old, current), listOf(override))
        assertEquals("学徒", entries.first { it.factId == "f0" }.value)
        assertEquals("剑士", entries.first { it.factId == "f1" }.value)
    }

    @Test
    fun delimiterCharactersDoNotAliasAnotherFact() {
        val first = fact("f1", "a|b", "c", "original")
        val second = fact("f2", "a", "b|c", "unrelated")
        val override = MemoryOverride(SCOPE, "f1", "a|b", "c", "edited", 1L, 5L)
        val entries = EffectiveMemoryResolver.resolve(listOf(first, second), listOf(override))
        assertEquals("unrelated", entries.first { it.factId == "f2" }.value)
    }

    @Test
    fun otherScopeOverrideCannotApplyEvenWithMatchingFactIdAndKey() {
        val override = MemoryOverride("other", "f1", "艾琳", "职业", "错误", 1L, 5L)
        val entry = EffectiveMemoryResolver.resolve(listOf(fact("f1", "艾琳", "职业", "法师")), listOf(override)).single()
        assertEquals("法师", entry.value)
    }

    private fun fact(
        id: String,
        subject: String,
        predicate: String,
        value: String,
    ) = TemporalMemoryFact(
        id = id,
        conversationId = SCOPE,
        sourceTurnId = "turn-1",
        subject = subject,
        predicate = predicate,
        value = value,
        observedAtEpochMillis = 1L,
        validFromEpochMillis = 1L,
    )

    private companion object {
        const val SCOPE = "scope"
    }
}
