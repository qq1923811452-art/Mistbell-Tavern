package com.mistbell.tavern.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionArchiveCodecTest {
    private val messages = listOf(
        PersistedChatMessage("m1", "user", "hello", 100L, PersistedChatMessage.DeliveryState.COMPLETE),
        PersistedChatMessage("m2", "assistant", "hi", 200L, PersistedChatMessage.DeliveryState.COMPLETE),
    )

    @Test fun jsonRoundTripPreservesSessionAndMessages() {
        val original = SessionArchive("session-1", "A session", messages)
        val decoded = SessionArchiveCodec.importAny(SessionArchiveCodec.exportJson(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test fun jsonlRoundTripAndTavernStyleFieldsAreSupported() {
        val original = SessionArchive("session-2", "Tavern session", messages)
        val decoded = SessionArchiveCodec.importAny(SessionArchiveCodec.exportJsonl(original))
        assertNotNull(decoded)
        assertEquals(original.id, decoded!!.id)
        assertEquals(original.title, decoded.title)
        assertEquals(original.messages, decoded.messages)
        val imported = SessionArchiveCodec.importAny(
            """{"role":"user","mes":"legacy content","send_date":"1970-01-01T00:00:01Z","is_user":true}""",
        )
        assertNotNull(imported)
        assertEquals("legacy content", imported!!.messages.single().content)
        assertEquals(1000L, imported.messages.single().createdAtEpochMillis)
    }

    @Test fun emptyJsonlSessionRoundTrips() {
        val original = SessionArchive("empty-session", "Empty session", emptyList())
        val exported = SessionArchiveCodec.exportJsonl(original)
        val decoded = SessionArchiveCodec.importAny(exported)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test fun malformedAndOversizedArchivesAreRejected() {
        assertNull(SessionArchiveCodec.importAny("not-json"))
        assertNull(SessionArchiveCodec.importAny("x".repeat(SessionArchiveCodec.MAX_IMPORT_BYTES + 1)))
    }
}
