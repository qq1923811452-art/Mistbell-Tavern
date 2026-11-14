package com.mistbell.tavern.v3.feature.era.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnProtocolTest {
    @Test
    fun parsesSnapshotWithButtonsAndStyles() {
        val raw =
            """
            {"type":"snapshot","payload":{
              "protocolVersion":13,"generation":4,"revision":9,"bgColor":"#101010",
              "awaiting":{"input":{"type":"IntValue"}},
              "lines":[{"align":"left","isLineEnd":true,"entries":[
                {"segments":[{"text":"[0] 挨拶","color":"#FFCE8A","bold":true}],
                 "button":{"value":0,"isInteger":true,"generation":4}}
              ]}]
            }}
            """.trimIndent()

        val ev = TurnWireParser.parse(raw) as HostWireEvent.Snapshot
        assertEquals(4L, ev.snapshot.generation)
        assertEquals(9L, ev.snapshot.revision)
        assertEquals("IntValue", ev.snapshot.awaiting.inputType)
        val entry = ev.snapshot.lines.single().entries.single()
        assertEquals("0", entry.button?.value)
        assertTrue(entry.segments.single().bold)
    }

    @Test
    fun appliesShiftHeadClearLineAndAppendInOrder() {
        val initial =
            listOf(
                TurnDisplayLine(1, emptyList(), null, true),
                TurnDisplayLine(2, emptyList(), null, true),
                TurnDisplayLine(3, emptyList(), null, true),
            )
        val newLine = TurnDisplayLine(4, emptyList(), null, true)
        val ops =
            listOf(
                TurnLineOp.ShiftHead(1),
                TurnLineOp.ClearLineDiff(1),
                TurnLineOp.Append(listOf(newLine)),
            )

        val result = applyLineOps(initial, ops)
        assertEquals(listOf(2L, 4L), result.map { it.uid })
    }

    @Test
    fun formatsFourLevelErrorInfoChain() {
        val raw =
            """
            {"type":"turn","payload":{
              "generation":1,"revision":2,
              "awaiting":{"ended":{"terminal":"Error"}},
              "error":"FATAL",
              "errorInfo":{"type":"InitEx","message":"root","inner":{"type":"CsvEx","message":"bad row"}}
            }}
            """.trimIndent()

        val ev = TurnWireParser.parse(raw) as HostWireEvent.Turn
        assertTrue(ev.record.awaiting.isEnded)
        assertEquals("Error", ev.record.awaiting.endedTerminal)
        val chain = ev.record.errorInfo?.formatChain()
        assertNotNull(chain)
        assertTrue(chain!!.contains("[InitEx] root"))
        assertTrue(chain.contains("[CsvEx] bad row"))
    }

    @Test
    fun handlesVoidAndTimedWaitProperly() {
        val raw =
            """
            {"type":"turn","payload":{
              "generation":2,"revision":5,
              "awaiting":{"wait":{"durationMs":3000,"displayTime":true}}
            }}
            """.trimIndent()
        val ev = TurnWireParser.parse(raw) as HostWireEvent.Turn
        assertFalse(ev.record.awaiting.acceptsInput)
        assertEquals(3000L, ev.record.awaiting.waitDurationMs)
        assertTrue(ev.record.awaiting.waitDisplayTime)
        assertNull(TurnWireParser.parse("broken json"))
    }
}
