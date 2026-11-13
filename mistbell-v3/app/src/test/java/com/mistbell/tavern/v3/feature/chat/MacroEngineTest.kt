package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MacroEngineTest {
    @Test
    fun replacesCharacterAndUserMacrosIgnoringCase() {
        val text = "{{char}} said hi to {{USER}}; <CHAR> smiles at <User>."
        assertEquals("Luna said hi to 你; Luna smiles at 你.", MacroEngine.resolve(text, "Luna"))
    }

    @Test
    fun leavesCharacterMacrosWhenNoNameIsAvailable() {
        assertEquals("{{char}} waits for 你.", MacroEngine.resolve("{{char}} waits for {{user}}.", null))
        assertEquals("{{char}} waits for 你.", MacroEngine.resolve("{{char}} waits for {{user}}.", "  "))
    }

    @Test
    fun supportsCustomUserNameAndLiteralDollarReplacement() {
        assertEquals("A$5 greets A$5", MacroEngine.resolve("{{char}} greets <user>", "A$5", "A$5"))
    }

    @Test
    fun returnsBlankTextUnchanged() {
        assertEquals("", MacroEngine.resolve("", "Luna"))
    }

    @Test
    fun randomMacroPicksOneNonEmptyCandidate() {
        val result = MacroEngine.resolve("{{random::alpha::beta::gamma}}", null, random = Random(7))
        assertTrue(result in setOf("alpha", "beta", "gamma"))
        assertEquals("only", MacroEngine.resolve("{{random::::only::}}", null))
        assertEquals("{{random::::}}", MacroEngine.resolve("{{random::::}}", null))
    }

    @Test
    fun rollMacroSupportsDiceFormatsAndRejectsInvalidSpecs() {
        assertTrue(MacroEngine.resolve("{{roll::2d6}}", null, random = Random(1)).toInt() in 2..12)
        assertTrue(MacroEngine.resolve("{{roll::d20}}", null, random = Random(1)).toInt() in 1..20)
        assertTrue(MacroEngine.resolve("{{roll::100}}", null, random = Random(1)).toInt() in 1..100)

        assertEquals("{{roll::21d6}}", MacroEngine.resolve("{{roll::21d6}}", null))
        assertEquals("{{roll::1d1}}", MacroEngine.resolve("{{roll::1d1}}", null))
        assertEquals("{{roll::d1001}}", MacroEngine.resolve("{{roll::d1001}}", null))
        assertEquals("{{roll::not-a-die}}", MacroEngine.resolve("{{roll::not-a-die}}", null))
    }
}
