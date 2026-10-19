package com.mistbell.tavern.v3.feature.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardJsonCodecTest {
    @Test fun parsesV2DataAndPreservesCharacterBook() {
        val card = CharacterCardJsonCodec.parse(
            """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Mira","description":"A mage","first_mes":"Hello","alternate_greetings":["Hi","Welcome"],"tags":["fantasy"],"character_book":{"name":"Lore","entries":[]}}}""",
        )
        assertNotNull(card)
        assertEquals("Mira", card!!.name)
        assertEquals("Hello", card.firstMessage)
        assertEquals(listOf("Hi", "Welcome"), card.alternateGreetings)
        assertEquals("Lore", org.json.JSONObject(card.characterBookJson!!).getString("name"))
    }

    @Test fun exportsAndParsesWithoutLosingKnownFields() {
        val original = CharacterCard(
            name = "Mira", description = "A mage", personality = "Curious",
            firstMessage = "Hello", tags = listOf("fantasy", "magic"),
            characterBookJson = """{"name":"Lore","entries":[]}""",
        )
        val roundTrip = CharacterCardJsonCodec.parse(CharacterCardJsonCodec.export(original))
        assertNotNull(roundTrip)
        assertEquals(original.name, roundTrip!!.name)
        assertEquals(original.description, roundTrip.description)
        assertEquals(original.personality, roundTrip.personality)
        assertEquals(original.firstMessage, roundTrip.firstMessage)
        assertEquals(original.tags, roundTrip.tags)
        val originalBook = org.json.JSONObject(original.characterBookJson!!)
        val roundTripBook = org.json.JSONObject(roundTrip.characterBookJson!!)
        assertEquals(originalBook.getString("name"), roundTripBook.getString("name"))
        assertEquals(originalBook.getJSONArray("entries").length(), roundTripBook.getJSONArray("entries").length())
    }

    @Test fun rejectsMalformedJsonAndCardsWithoutNames() {
        assertNull(CharacterCardJsonCodec.parse("not-json"))
        assertNull(CharacterCardJsonCodec.parse("""{"data":{"description":"No name"}}"""))
    }
    @Test fun stableDefinitionIsCanonicalAndExcludesConversationOpening() {
        val card = CharacterCard(
            name = " Mira ",
            description = " A mage ",
            personality = " Curious ",
            firstMessage = "This is a volatile opening.",
            exampleDialogue = "Example: The mage smiles.",
            systemPrompt = "Be kind",
            postHistoryInstructions = "Stay in character",
        )

        assertEquals(
            "Name: Mira\n\nDescription: A mage\n\nPersonality: Curious\n\nExample dialogue:\nExample: The mage smiles.\n\nSystem instructions: Be kind\n\nPost-history instructions: Stay in character",
            card.stableDefinition(),
        )
        assertTrue(!card.stableDefinition().contains("volatile opening"))
        assertTrue(card.stableDefinition().contains("Example dialogue:"))
    }

}
