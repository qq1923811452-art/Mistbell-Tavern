package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
