package com.mistbell.tavern.v3.feature.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterCardFormatTest {
    @Test
    fun canonicalCodecRoundTripsWithoutChangingStableDefinition() {
        val card = CharacterCard(
            id = "mira",
            name = "Mira",
            description = "A cartographer.",
            personality = "Curious",
            scenario = "A rainy port.",
            firstMessage = "Welcome.",
            alternateGreetings = listOf("Hello again.", "Good evening."),
            tags = listOf("fantasy", "guide"),
        )

        val encoded = CharacterCardCodec.encode(card)
        val decoded = CharacterCardCodec.decode(encoded)

        assertEquals(card, decoded)
        assertEquals(card.stableDefinition(), decoded.stableDefinition())
        assertEquals(encoded, CharacterCardCodec.encode(decoded))
    }

    @Test
    fun importsV2NestedDataAndSeparatesEmbeddedWorldbook() {
        val result = LegacyCharacterCardImporter.import(
            """{
              "spec":"chara_card_v2",
              "data":{
                "name":"Mira",
                "description":"A cartographer.",
                "personality":"Curious",
                "first_mes":"Welcome.",
                "alternate_greetings":["Hello again."],
                "tags":["fantasy"],
                "character_book":{"entries":{"1":{"uid":1,"comment":"Port","content":"The port is old.","constant":true,"key":[]}}}
              }
            }""",
        )

        assertEquals("Mira", result.card.name)
        assertEquals("Welcome.", result.card.firstMessage)
        assertEquals(listOf("Hello again."), result.card.alternateGreetings)
        assertNotNull(result.embeddedWorldbook)
        assertEquals("The port is old.", result.embeddedWorldbook!!.entries.single().content)
    }

    @Test
    fun emptyEmbeddedCharacterBookDoesNotRejectOtherwiseValidCard() {
        val result = LegacyCharacterCardImporter.import(
            """{"name":"Mira","character_book":{"name":"Empty book"}}""",
        )

        assertEquals("Mira", result.card.name)
        assertNull(result.embeddedWorldbook)
    }

    @Test
    fun importsV1RootFieldsWithoutInventingAnEmbeddedWorldbook() {
        val result = LegacyCharacterCardImporter.import(
            """{"name":"Rowan","description":"A ranger.","first_mes":"Stay close.","mes_example":"{{char}}: Ready.","tags":[]}""",
        )

        assertEquals("Rowan", result.card.name)
        assertEquals("Stay close.", result.card.firstMessage)
        assertEquals("{{char}}: Ready.", result.card.exampleDialogue)
        assertNull(result.embeddedWorldbook)
    }
}
