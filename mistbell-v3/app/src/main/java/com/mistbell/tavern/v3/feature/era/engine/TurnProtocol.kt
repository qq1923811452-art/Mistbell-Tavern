package com.mistbell.tavern.v3.feature.era.engine

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface HostWireEvent {
    data class Status(val state: String, val detail: String?) : HostWireEvent

    data class Snapshot(val snapshot: TurnSnapshot) : HostWireEvent

    data class Turn(val record: TurnRecord) : HostWireEvent

    data class HostError(val code: String, val message: String) : HostWireEvent

    data class Stopped(val code: Int, val reason: String?) : HostWireEvent
}

internal data class TurnSnapshot(
    val protocolVersion: Int,
    val generation: Long,
    val revision: Long,
    val bgColor: String?,
    val awaiting: TurnAwaiting,
    val lines: List<TurnDisplayLine>,
)

internal data class TurnRecord(
    val diff: TurnDisplayDiff?,
    val awaiting: TurnAwaiting,
    val error: String?,
    val errorInfo: TurnErrorInfo?,
    val generation: Long,
    val revision: Long,
)

internal data class TurnErrorInfo(
    val type: String,
    val message: String,
    val inner: TurnErrorInfo?,
) {
    fun formatChain(maxDepth: Int = 4): String {
        val sb = StringBuilder()
        var cur: TurnErrorInfo? = this
        var depth = 0
        while (cur != null && depth < maxDepth) {
            if (depth > 0) sb.append("\n  Caused by: ")
            sb.append('[').append(cur.type).append("] ").append(cur.message)
            cur = cur.inner
            depth++
        }
        return sb.toString()
    }
}

internal data class TurnAwaiting(
    val inputType: String?,
    val waitDurationMs: Long?,
    val waitDisplayTime: Boolean,
    val waitTimeUpMessage: String?,
    val waitTimedOut: Boolean,
    val endedTerminal: String?,
) {
    val isEnded: Boolean get() = endedTerminal != null
    val acceptsInput: Boolean get() = inputType != null && inputType != "Void" && !isEnded
    val isTapToAdvance: Boolean get() = inputType == "EnterKey" || inputType == "AnyKey"
    val needsTextValue: Boolean
        get() = inputType == "IntValue" || inputType == "StrValue" || inputType == "AnyValue"
}

internal data class TurnDisplayDiff(
    val lineOps: List<TurnLineOp>,
    val bgColor: String?,
)

internal sealed interface TurnLineOp {
    data class Append(val newLines: List<TurnDisplayLine>) : TurnLineOp

    data class ClearLineDiff(val clearCount: Int) : TurnLineOp

    data object ClearScreen : TurnLineOp

    data class ShiftHead(val count: Int) : TurnLineOp
}

internal data class TurnDisplayLine(
    val uid: Long,
    val entries: List<TurnDisplayEntry>,
    val align: String?,
    val isLineEnd: Boolean,
)

internal data class TurnDisplayEntry(
    val segments: List<TurnPrintSegment>,
    val button: TurnButton?,
)

internal data class TurnPrintSegment(
    val text: String,
    val color: String?,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikeout: Boolean,
    val hasGraphic: Boolean,
)

internal data class TurnButton(
    val value: String,
    val isInteger: Boolean,
    val generation: Long,
)

internal object TurnWireParser {
    private const val PROTOCOL_VERSION = 13
    private var nextLineUid = 1L

    @Suppress("ReturnCount")
    fun parse(raw: String): HostWireEvent? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return when (root.optString("type")) {
            "status" ->
                HostWireEvent.Status(
                    state = root.optString("state"),
                    detail = root.optString("detail").takeIf { it.isNotEmpty() },
                )
            "snapshot" ->
                root.optJSONObject("payload")?.let {
                    HostWireEvent.Snapshot(parseSnapshot(it))
                }
            "turn" ->
                root.optJSONObject("payload")?.let {
                    HostWireEvent.Turn(parseTurnRecord(it))
                }
            "error" ->
                HostWireEvent.HostError(
                    code = root.optString("code", "ERROR"),
                    message = root.optString("message", "未知错误"),
                )
            "stopped" ->
                HostWireEvent.Stopped(
                    code = root.optInt("code", 0),
                    reason = root.optString("reason").takeIf { it.isNotEmpty() },
                )
            else -> null
        }
    }

    private fun parseSnapshot(obj: JSONObject): TurnSnapshot {
        return TurnSnapshot(
            protocolVersion = obj.optInt("protocolVersion", PROTOCOL_VERSION),
            generation = obj.optLong("generation", 0L),
            revision = obj.optLong("revision", 0L),
            bgColor = obj.optString("bgColor").takeIf { it.isNotEmpty() },
            awaiting = parseAwaiting(obj.optJSONObject("awaiting")),
            lines = parseLines(obj.optJSONArray("lines")),
        )
    }

    private fun parseTurnRecord(obj: JSONObject): TurnRecord {
        return TurnRecord(
            diff = obj.optJSONObject("diff")?.let(::parseDiff),
            awaiting = parseAwaiting(obj.optJSONObject("awaiting")),
            error = obj.optString("error").takeIf { it.isNotEmpty() },
            errorInfo = obj.optJSONObject("errorInfo")?.let(::parseErrorInfo),
            generation = obj.optLong("generation", 0L),
            revision = obj.optLong("revision", 0L),
        )
    }

    private fun parseErrorInfo(obj: JSONObject?): TurnErrorInfo? {
        if (obj == null) return null
        return TurnErrorInfo(
            type = obj.optString("type", "Exception"),
            message = obj.optString("message", ""),
            inner = parseErrorInfo(obj.optJSONObject("inner")),
        )
    }

    private fun parseAwaiting(obj: JSONObject?): TurnAwaiting {
        if (obj == null) {
            return TurnAwaiting(null, null, false, null, false, null)
        }
        val input = obj.optJSONObject("input")
        val wait = obj.optJSONObject("wait")
        val ended = obj.optJSONObject("ended")
        return TurnAwaiting(
            inputType = input?.optString("type")?.takeIf { it.isNotEmpty() },
            waitDurationMs = if (wait != null && wait.has("durationMs")) wait.optLong("durationMs") else null,
            waitDisplayTime = wait?.optBoolean("displayTime", false) ?: false,
            waitTimeUpMessage = wait?.optString("timeUpMessage")?.takeIf { it.isNotEmpty() },
            waitTimedOut = wait?.optBoolean("timedOut", false) ?: false,
            endedTerminal = ended?.optString("terminal")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseDiff(obj: JSONObject): TurnDisplayDiff {
        val opsArray = obj.optJSONArray("lineOps")
        val ops = ArrayList<TurnLineOp>(opsArray?.length() ?: 0)
        if (opsArray != null) {
            for (i in 0 until opsArray.length()) {
                val item = opsArray.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "append" -> ops += TurnLineOp.Append(parseLines(item.optJSONArray("newLines")))
                    "clear_line_diff" -> ops += TurnLineOp.ClearLineDiff(item.optInt("clearCount", 0))
                    "clear_screen" -> ops += TurnLineOp.ClearScreen
                    "shift_head" -> ops += TurnLineOp.ShiftHead(item.optInt("count", 0))
                }
            }
        }
        return TurnDisplayDiff(
            lineOps = ops,
            bgColor = obj.optString("bgColor").takeIf { it.isNotEmpty() },
        )
    }

    private fun parseLines(array: JSONArray?): List<TurnDisplayLine> {
        if (array == null) return emptyList()
        val result = ArrayList<TurnDisplayLine>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val entriesArray = obj.optJSONArray("entries")
            val entries = ArrayList<TurnDisplayEntry>(entriesArray?.length() ?: 0)
            if (entriesArray != null) {
                for (j in 0 until entriesArray.length()) {
                    val eObj = entriesArray.optJSONObject(j) ?: continue
                    entries += parseEntry(eObj)
                }
            }
            result +=
                TurnDisplayLine(
                    uid = nextLineUid++,
                    entries = entries,
                    align = obj.optString("align").takeIf { it.isNotEmpty() },
                    isLineEnd = obj.optBoolean("isLineEnd", true),
                )
        }
        return result
    }

    private fun parseEntry(obj: JSONObject): TurnDisplayEntry {
        val segArray = obj.optJSONArray("segments")
        val segments = ArrayList<TurnPrintSegment>(segArray?.length() ?: 0)
        if (segArray != null) {
            for (k in 0 until segArray.length()) {
                val s = segArray.optJSONObject(k) ?: continue
                val hasGraphic = s.has("image") || s.has("shape")
                segments +=
                    TurnPrintSegment(
                        text = if (hasGraphic) "" else s.optString("text", ""),
                        color = s.optString("color").takeIf { it.isNotEmpty() },
                        bold = s.optBoolean("bold", false),
                        italic = s.optBoolean("italic", false),
                        underline = s.optBoolean("underline", false),
                        strikeout = s.optBoolean("strikeout", false),
                        hasGraphic = hasGraphic,
                    )
            }
        }
        val bObj = obj.optJSONObject("button")
        val button =
            bObj?.let { b ->
                val rawVal = b.opt("value")?.toString() ?: ""
                TurnButton(
                    value = rawVal,
                    isInteger = b.optBoolean("isInteger", false),
                    generation = b.optLong("generation", 0L),
                )
            }
        return TurnDisplayEntry(segments = segments, button = button)
    }
}

internal object TurnWireCommand {
    fun input(
        value: String,
        generation: Long?,
    ): String =
        JSONObject()
            .put("type", "input")
            .put("value", value)
            .apply { if (generation != null) put("generation", generation) }
            .toString()

    fun stop(): String = JSONObject().put("type", "stop").toString()
}

internal fun applyLineOps(
    current: List<TurnDisplayLine>,
    ops: List<TurnLineOp>,
    maxLines: Int = 1500,
): List<TurnDisplayLine> {
    val next = current.toMutableList()
    for (op in ops) {
        when (op) {
            is TurnLineOp.ShiftHead -> {
                val c = op.count.coerceAtLeast(0)
                if (c >= next.size) {
                    next.clear()
                } else if (c > 0) {
                    next.subList(0, c).clear()
                }
            }
            is TurnLineOp.ClearLineDiff -> {
                val c = op.clearCount.coerceAtLeast(0)
                val keep = (next.size - c).coerceAtLeast(0)
                if (keep < next.size) next.subList(keep, next.size).clear()
            }
            TurnLineOp.ClearScreen -> next.clear()
            is TurnLineOp.Append -> next.addAll(op.newLines)
        }
    }
    if (next.size > maxLines) {
        next.subList(0, next.size - maxLines).clear()
    }
    return next
}
