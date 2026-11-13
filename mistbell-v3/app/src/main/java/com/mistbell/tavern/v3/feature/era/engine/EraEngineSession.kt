@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package com.mistbell.tavern.v3.feature.era.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.mistbell.tavern.v3.BuildConfig
import com.mistbell.tavern.v3.feature.era.EraGameInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** 游戏里的一行文本。lineId 单调递增，`legacy.clear` 靠它裁剪。 */
internal data class EraLine(
    val id: Long,
    val text: String,
)

internal data class EraGameState(
    val connected: Boolean = false,
    val statusText: String = "正在准备游戏…",
    val lines: List<EraLine> = emptyList(),
    val input: EwpInputRequest? = null,
    val error: String? = null,
    val detail: String? = null,
    val warningCount: Int = 0,
    val lineCount: Int = 0,
)

/**
 * 一次游戏会话：打包 → 绑定引擎服务 → 连 EWP → 收事件 → 回输入。
 *
 * 引擎是独立 APK；绑定期间它的进程被前台客户端拉住，解绑即停。
 */
internal class EraEngineSession(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = EwpClient()
    private val packager = EraGamePackager(appContext.cacheDir)
    private val _state = MutableStateFlow(EraGameState())
    val state: StateFlow<EraGameState> = _state.asStateFlow()

    private var serviceBound = false
    private var started = false
    private var pendingPort = 0
    private var request: EwpInputRequest? = null
    private var reading: Job? = null
    private var sessionJob: Job? = null

    private val handler =
        Handler(Looper.getMainLooper()) { message: Message ->
            if (message.what == EraEngineContract.MSG_STATUS) {
                val state = message.data?.getString("state")
                val detail = message.data?.getString("detail")
                state?.let { onEngineStatus(it, detail) }
            }
            true
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                serviceBound = true
                reading = scope.launch { connectEwp() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
                fail("引擎进程已断开")
            }

            override fun onBindingDied(name: ComponentName?) {
                serviceBound = false
                fail("引擎组件不可用，请重新安装")
            }
        }

    fun start(game: EraGameInstall) {
        if (started) return
        started = true
        sessionJob =
            scope.launch {
                try {
                    _state.update { it.copy(statusText = "正在打包 ${game.name}…") }
                    val zip = packGame(game)
                    pendingPort = pickPort()
                    _state.update { it.copy(statusText = "正在唤起引擎…") }
                    bindEngine(game, zip, pendingPort)
                } catch (error: Exception) {
                    fail(error.message ?: "启动引擎失败")
                }
            }
    }

    /** 把界面上的相对坐标换算成引擎 render 像素后发送点击。 */
    fun clickNormalized(
        fractionX: Float,
        fractionY: Float,
    ) {
        val x = (fractionX.coerceIn(0f, 1f) * VIEWPORT_WIDTH).toInt()
        val y = (fractionY.coerceIn(0f, 1f) * viewportHeight()).toInt()
        client.send(EwpCommand.click(x, y))
    }

    private fun packGame(game: EraGameInstall): File =
        packager.pack(
            game = game,
            onProgress = { done, total ->
                _state.update { it.copy(statusText = "正在打包 $done/$total 个文件…") }
            },
        )

    fun submit(
        value: String,
        via: String,
    ) {
        val current = request ?: return
        request = null
        _state.update { it.copy(input = null, statusText = "等待引擎结算…") }
        client.send(EwpCommand.submit(current.requestId, value, via))
    }

    /** 用户点「重试」：拆掉当前绑定，重新走一遍打包 → 绑定 → 握手。 */
    fun retry(game: EraGameInstall) {
        sessionJob?.cancel()
        sessionJob = null
        started = false
        request = null
        reading?.cancel()
        runCatching { client.send(EwpCommand.quit()) }
        client.close()
        if (serviceBound) {
            runCatching { appContext.unbindService(connection) }
            serviceBound = false
        }
        _state.value = EraGameState(statusText = "正在重启引擎…")
        start(game)
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        started = false
        request = null
        runCatching { client.send(EwpCommand.quit()) }
        client.close()
        if (serviceBound) {
            runCatching { appContext.unbindService(connection) }
            serviceBound = false
        }
        reading?.cancel()
        scope.cancel()
    }

    private fun bindEngine(
        game: EraGameInstall,
        zip: File,
        port: Int,
    ) {
        val messenger = Messenger(handler)
        // 单包时两个组件同 UID，直接给路径最省事；跨应用才需要 fd（部分 ROM 禁止跨应用传 fd）。
        val merged = BuildConfig.ERA_MERGED
        val descriptor =
            if (merged) null else ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY)
        val intent =
            Intent().apply {
                component = EraEngineContract.componentFor(appContext)
                putExtra(EraEngineContract.EXTRA_REPLY_TO, messenger)
                putExtra(EraEngineContract.EXTRA_PORT, port)
                putExtra(EraEngineContract.EXTRA_GAME_NAME, game.name)
                putExtra(EraEngineContract.EXTRA_PACK_VERSION, packager.versionKey(game))
                if (merged) {
                    putExtra(EraEngineContract.EXTRA_ZIP_PATH, zip.absolutePath)
                } else if (descriptor != null) {
                    putExtra(EraEngineContract.EXTRA_ZIP_FD, descriptor)
                }
            }
        val target = EraEngineContract.componentFor(appContext)
        val installed =
            runCatching {
                appContext.packageManager.getPackageInfo(target.packageName, 0)
            }.isSuccess
        var bound = false
        val bindError =
            runCatching {
                bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.exceptionOrNull()
        bound = bound && bindError == null
        // Binder 已把 fd dup 给引擎进程，本地这份可以立刻关掉，避免每次进游戏漏一个 fd。
        runCatching { descriptor?.close() }
        if (!installed) {
            fail("引擎组件未安装（${target.packageName}）")
        } else if (bindError != null) {
            fail("绑定引擎被拒：${bindError.javaClass.simpleName} ${bindError.message ?: ""}".trim())
        } else if (!bound) {
            fail("引擎组件存在但绑定失败（${target.flattenToShortString()}）")
        }
    }

    private suspend fun connectEwp() {
        _state.update { it.copy(statusText = "等待引擎监听 $pendingPort…") }
        val reader = client.open(pendingPort, HANDSHAKE_TIMEOUT_MS)
        if (reader == null) {
            fail("引擎在 ${HANDSHAKE_TIMEOUT_MS / MILLIS_PER_SECOND} 秒内没有就绪")
            return
        }
        client.send(EwpCommand.helloOk(viewportWidth(), viewportHeight()))
        _state.update { it.copy(connected = true, statusText = "已连接引擎") }
        readLoop(reader)
    }

    private fun readLoop(reader: BufferedReader) {
        try {
            while (scope.isActive) {
                val raw = reader.readLine() ?: break
                EwpParser.parse(raw)?.let(::handle)
            }
        } catch (error: Exception) {
            if (scope.isActive) fail(error.message ?: "与引擎的连接中断")
        }
    }

    private fun handle(event: EwpEvent) {
        when (event) {
            is EwpEvent.Hello -> _state.update { it.copy(statusText = "引擎 ${event.proto}") }
            EwpEvent.Ready -> _state.update { it.copy(statusText = "游戏已启动") }
            is EwpEvent.Line -> appendLine(event.id, event.text)
            is EwpEvent.Clear -> clearLines(event.fromId, event.count)
            is EwpEvent.Input -> {
                request = event.request
                _state.update { it.copy(input = event.request, statusText = "轮到你了") }
            }
            is EwpEvent.Commit -> client.send(EwpCommand.ack(event.frameId))
            is EwpEvent.Dialog -> appendLine(-1, "【系统】${event.text}")
            is EwpEvent.Failure -> _state.update { it.copy(error = event.detail) }
            EwpEvent.Exit -> _state.update { it.copy(statusText = "引擎已退出") }
        }
    }

    private fun appendLine(
        id: Long,
        text: String,
    ) {
        // eraTW 首次加载会刷几十条字符卡警告，留着会冲掉正文；只计数，正片从标题页开始。
        if (text.startsWith("警告") || text.startsWith("警告Lv")) {
            _state.update { it.copy(warningCount = it.warningCount + 1) }
            return
        }
        _state.update { current ->
            val next = current.lines + EraLine(id, text)
            current.copy(
                lines = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next,
                lineCount = current.lineCount + 1,
            )
        }
    }

    private fun clearLines(
        fromId: Long,
        count: Int,
    ) {
        val until = if (count > 0) fromId + count else Long.MAX_VALUE
        _state.update { current ->
            current.copy(lines = current.lines.filterNot { it.id in fromId until until })
        }
    }

    private fun onEngineStatus(
        state: String,
        detail: String? = null,
    ) {
        _state.update { it.copy(detail = detail ?: it.detail) }
        if (detail != null && detail.startsWith("no_game_installed")) {
            fail("引擎里没有游戏包，请重新导入 ZIP")
            return
        }
        when (state) {
            EraEngineContract.STATE_IMPORTING -> _state.update { it.copy(statusText = "引擎正在解包游戏…") }
            EraEngineContract.STATE_IMPORTED -> _state.update { it.copy(statusText = "游戏包已就位") }
            EraEngineContract.STATE_STARTING -> _state.update { it.copy(statusText = "引擎正在启动…") }
            EraEngineContract.STATE_RUNNING -> _state.update { it.copy(statusText = "引擎运行中") }
            EraEngineContract.STATE_STOPPED ->
                _state.update {
                    it.copy(statusText = "引擎已停止" + (detail?.let { d -> "（$d）" } ?: ""))
                }
            EraEngineContract.STATE_ERROR -> fail("引擎启动失败")
            else -> Unit
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message, statusText = message) }
    }

    private fun viewportWidth(): Int = VIEWPORT_WIDTH

    private fun viewportHeight(): Int {
        val metrics = appContext.resources.displayMetrics
        if (metrics.widthPixels <= 0) return VIEWPORT_HEIGHT_FALLBACK
        return VIEWPORT_WIDTH * metrics.heightPixels / metrics.widthPixels
    }

    private fun pickPort(): Int =
        runCatching {
            ServerSocket(EPHEMERAL_PORT, LISTEN_BACKLOG, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.getOrDefault(DEFAULT_PORT)

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 180_000L
        const val MILLIS_PER_SECOND = 1000
        const val LISTEN_BACKLOG = 1
        const val EPHEMERAL_PORT = 0
        const val DEFAULT_PORT = 5177
        const val MAX_LINES = 600
        const val VIEWPORT_WIDTH = 960
        const val VIEWPORT_HEIGHT_FALLBACK = 540
    }
}
