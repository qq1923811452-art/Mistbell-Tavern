package com.mistbell.tavern.v3.feature.era.engine

import org.json.JSONArray
import org.json.JSONObject

/** EWP/0.3 事件（前端视角只需要这几类）。 */
internal sealed interface EwpEvent {
    data class Hello(
        val proto: String,
    ) : EwpEvent

    data object Ready : EwpEvent

    data class Line(
        val id: Long,
        val text: String,
    ) : EwpEvent

    data class Clear(
        val fromId: Long,
        val count: Int,
    ) : EwpEvent

    data class Input(
        val request: EwpInputRequest,
    ) : EwpEvent

    data class Commit(
        val frameId: Long,
    ) : EwpEvent

    data class Dialog(
        val text: String,
    ) : EwpEvent

    data class Failure(
        val detail: String,
    ) : EwpEvent

    data object Exit : EwpEvent
}

/** 一次等待输入：按钮列表 + 是否需要文本输入。 */
internal data class EwpInputRequest(
    val requestId: Long,
    val type: String,
    val needsValue: Boolean,
    val timed: Boolean,
    val buttons: List<EwpButton>,
)

internal data class EwpButton(
    val index: Int,
    val text: String,
    val value: String,
)

/**
 * 引擎事件是 JSON-Lines；这里只解析前端要用的字段，未知事件一律返回 null（向前兼容）。
 */
internal object EwpParser {
    @Suppress("ReturnCount")
    fun parse(raw: String): EwpEvent? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return when (root.optString("t")) {
            "hello" -> EwpEvent.Hello(proto = root.optString("proto"))
            "ready" -> EwpEvent.Ready
            "legacy.line" -> EwpEvent.Line(id = root.optLong("lineId"), text = root.optString("text"))
            "legacy.clear" -> EwpEvent.Clear(fromId = root.optLong("fromLineId"), count = root.optInt("count", 0))
            "input.request" -> EwpEvent.Input(request = parseInput(root))
            "frame.commit" -> EwpEvent.Commit(frameId = root.optLong("frameId"))
            "dialog.request" -> EwpEvent.Dialog(text = root.optString("text"))
            "error" -> EwpEvent.Failure(detail = errorDetail(root))
            "exit" -> EwpEvent.Exit
            else -> null
        }
    }

    private fun errorDetail(root: JSONObject): String {
        val code = root.optString("code")
        val detail = root.optString("detail")
        return if (detail.isEmpty()) code else "$code: $detail"
    }

    private fun parseInput(root: JSONObject): EwpInputRequest {
        val buttons = ArrayList<EwpButton>()
        val array = root.optJSONArray("buttons")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                buttons += parseButton(item, index)
            }
        }
        return EwpInputRequest(
            requestId = root.optLong("reqId"),
            type = root.optString("type"),
            needsValue = root.optBoolean("needValue", false),
            timed = root.optInt("timeoutMs", 0) > 0,
            buttons = buttons,
        )
    }

    private fun parseButton(
        item: JSONObject,
        fallbackIndex: Int,
    ): EwpButton {
        val text = item.optString("text")
        val value = item.optString("value", text)
        return EwpButton(index = item.optInt("index", fallbackIndex), text = text, value = value)
    }
}

/** 前端 → 引擎的命令。 */
internal object EwpCommand {
    fun helloOk(
        width: Int,
        height: Int,
    ): String =
        JSONObject()
            .put("t", "hello.ok")
            .put("caps", JSONArray(FRONTEND_CAPS))
            .put("viewport", JSONObject().put("width", width).put("height", height))
            .put("mode", JSONObject().put("semantic", true).put("pixel", false))
            .toString()

    fun submit(
        requestId: Long,
        value: String,
        via: String,
    ): String =
        JSONObject()
            .put("t", "input.submit")
            .put("reqId", requestId)
            .put("value", value)
            .put("via", via)
            .toString()

    fun ack(frameId: Long): String = JSONObject().put("t", "view.ack").put("frameId", frameId).toString()

    fun quit(): String = JSONObject().put("t", "sys.quit").toString()

    /** 直接按 Enter（对应引擎的 sys.enter，用于"按 Enter 继续"）。 */
    fun enter(): String = JSONObject().put("t", "sys.enter").toString()

    /** 手指点击 → 引擎鼠标点击（需要上游安装 sys.click 补丁）。 */
    fun click(
        x: Int,
        y: Int,
    ): String = JSONObject().put("t", "sys.click").put("x", x).put("y", y).toString()

    /** 只声明真的会处理的能力；引擎会据此降级，不会发我们渲染不了的事件。 */
    private val FRONTEND_CAPS =
        listOf(
            "legacy.lines",
            "input.scope",
            "snapshot",
            "frame.commit",
            "view.ack",
            "viewport.report",
            "meta.metrics",
            "input.rect",
        )
}
