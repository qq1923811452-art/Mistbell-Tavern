package com.mistbell.tavern.v3.feature.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCharacterCardImporterTest {
    @Test
    fun importsNestedV2AndPreservesEmbeddedCharacterBook() {
        val card = LegacyCharacterCardImporter.import(
            """{"spec":"chara_card_v2","data":{"name":"Mira","first_mes":"Hello","alternate_greetings":["Hi","Welcome"],"tags":["fantasy"],"character_book":{"name":"Lore","entries":[]}}}""",
        )

        assertEquals("Mira", card.name)
        assertEquals("Hello", card.firstMessage)
        assertEquals(listOf("Hi", "Welcome"), card.alternateGreetings)
        assertEquals(listOf("fantasy"), card.tags)
        assertNotNull(card.characterBookJson)
        assertEquals("Lore", org.json.JSONObject(card.characterBookJson!!).getString("name"))
    }

    @Test
    fun importsLegacyRootFieldsAndAliases() {
        val card = LegacyCharacterCardImporter.import(
            """{"name":"Rowan","firstMessage":"Stay close.","exampleDialogue":"{{char}}: Ready.","creatorcomment":"Notes"}""",
        )

        assertEquals("Rowan", card.name)
        assertEquals("Stay close.", card.firstMessage)
        assertEquals("{{char}}: Ready.", card.exampleDialogue)
        assertEquals("Notes", card.creatorNotes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCardsWithoutAName() {
        LegacyCharacterCardImporter.import("""{"data":{"description":"No name"}}""")
    }
}
