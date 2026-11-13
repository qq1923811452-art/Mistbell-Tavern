package com.mistbell.tavern.v3.feature.era.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EwpParserTest {
    @Test
    fun parsesLegacyLineAndKeepsLineId() {
        val event = EwpParser.parse("""{"t":"legacy.line","lineId":42,"gen":0,"text":"こんにちは"}""")

        assertEquals(EwpEvent.Line(id = 42, text = "こんにちは"), event)
    }

    @Test
    fun parsesInputRequestWithButtons() {
        val raw =
            """
            {"t":"input.request","reqId":7,"type":"AnyKey","generation":3,
             "needValue":true,"timeoutMs":15000,
             "buttons":[{"index":0,"text":"[0] 挨拶","value":"0","isInteger":true},
                        {"index":1,"text":"[1] 移動","value":"1","isInteger":true}]}
            """.trimIndent()

        val event = EwpParser.parse(raw)
        assertTrue(event is EwpEvent.Input)
        val request = (event as EwpEvent.Input).request

        assertEquals(7L, request.requestId)
        assertEquals("AnyKey", request.type)
        assertTrue(request.needsValue)
        assertTrue(request.timed)
        assertEquals(listOf("0", "1"), request.buttons.map { it.value })
        assertEquals("[0] 挨拶", request.buttons.first().text)
    }

    @Test
    fun parsesClearAndCommit() {
        val clear = EwpParser.parse("""{"t":"legacy.clear","fromLineId":5,"count":3}""")
        assertEquals(EwpEvent.Clear(fromId = 5, count = 3), clear)

        val commit = EwpParser.parse("""{"t":"frame.commit","frameId":9,"events":2}""")
        assertEquals(EwpEvent.Commit(frameId = 9), commit)
    }

    @Test
    fun ignoresUnknownAndBrokenPayloads() {
        assertNull(EwpParser.parse("""{"t":"channel.begin","id":"x","rev":1}"""))
        assertNull(EwpParser.parse("not json"))
        assertNull(EwpParser.parse(""))
    }

    @Test
    fun helloOkOnlyDeclaresFrontendCaps() {
        val json = EwpCommand.helloOk(width = 960, height = 1600)

        assertTrue(json.contains("\"t\":\"hello.ok\""))
        assertTrue(json.contains("\"viewport\""))
        assertTrue(json.contains("\"input.rect\""))
        assertTrue(!json.contains("\"pixel.frame\""))
    }
}
