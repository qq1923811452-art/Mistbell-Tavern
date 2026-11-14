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

internal data class EraGameState(
    val connected: Boolean = false,
    val statusText: String = "正在准备游戏…",
    val lines: List<TurnDisplayLine> = emptyList(),
    val bgColor: String? = null,
    val bgImages: List<TurnBgImage> = emptyList(),
    val awaiting: TurnAwaiting? = null,
    val generation: Long = 0L,
    val revision: Long = 0L,
    val inputHint: String? = null,
    val error: String? = null,
    val detail: String? = null,
    val lineCount: Int = 0,
    val gameDir: String? = null,
) {
    /** 抽取当前 generation 下屏幕上出现的可点击按钮，供底部大按钮条使用。 */
    @Suppress("LoopWithTooManyJumpStatements")
    val activeButtons: List<TurnButtonItem>
        get() {
            if (awaiting?.acceptsInput != true) return emptyList()
            val seen = LinkedHashMap<String, TurnButtonItem>()
            for (line in lines.asReversed()) {
                for (entry in line.entries) {
                    val btn = entry.button ?: continue
                    if (btn.generation != generation) continue
                    val label = entry.segments.joinToString("") { it.text }.trim()
                    if (label.isNotEmpty() && !seen.containsKey(btn.value)) {
                        seen[btn.value] = TurnButtonItem(label = label, button = btn)
                    }
                }
                if (seen.size >= MAX_QUICK_BUTTONS) break
            }
            return seen.values.toList().reversed()
        }

    private companion object {
        const val MAX_QUICK_BUTTONS = 24
    }
}

internal data class TurnButtonItem(
    val label: String,
    val button: TurnButton,
)

internal object EraEngineManager {
    @Volatile
    private var instance: EraEngineSession? = null

    fun get(context: Context): EraEngineSession =
        instance ?: synchronized(this) {
            instance ?: EraEngineSession(context.applicationContext).also { instance = it }
        }
}

internal class EraEngineSession(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = EraSocketClient()
    private val packager = EraGamePackager(appContext.cacheDir)
    private val _state = MutableStateFlow(EraGameState())
    val state: StateFlow<EraGameState> = _state.asStateFlow()

    private var activePackKey: String? = null
    private var serviceBound = false
    private var serviceMessenger: Messenger? = null
    private var pendingPort = 0
    private var readingJob: Job? = null
    private var startJob: Job? = null
    private var explicitStop = false

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
                serviceMessenger = service?.let(::Messenger)
                connectSocket()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
                serviceMessenger = null
                if (!explicitStop) {
                    fail("引擎进程已意外断开")
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                serviceBound = false
                serviceMessenger = null
                if (!explicitStop) {
                    fail("引擎组件不可用，请重试或重新安装")
                }
            }
        }

    /** 确保给定游戏包正在运行；若是同一包且活着，只恢复 Socket 同步画面，不重启加载。 */
    fun ensureStarted(game: EraGameInstall) {
        val packKey = "${game.name}::${packager.versionKey(game)}"
        if (activePackKey == packKey && serviceBound && _state.value.error == null) {
            if (!_state.value.connected) {
                connectSocket()
            }
            return
        }
        startInternal(game, packKey, forceRestart = activePackKey != null && activePackKey != packKey)
    }

    /** 进入/离开游戏页时只断开或恢复 TCP 渲染连接，不杀后台引擎进程（避免重复加载）。 */
    fun detachUiSocket() {
        readingJob?.cancel()
        readingJob = null
        client.close()
        _state.update { it.copy(connected = false) }
    }

    fun retry(game: EraGameInstall) {
        val packKey = "${game.name}::${packager.versionKey(game)}"
        startInternal(game, packKey, forceRestart = true)
    }

    /** 用户手动关闭引擎释放内存。 */
    fun shutdown() {
        explicitStop = true
        activePackKey = null
        startJob?.cancel()
        startJob = null
        readingJob?.cancel()
        readingJob = null
        runCatching { client.send(TurnWireCommand.stop()) }
        client.close()
        runCatching {
            serviceMessenger?.send(Message.obtain(null, EraEngineContract.MSG_STOP))
        }
        serviceMessenger = null
        if (serviceBound) {
            runCatching { appContext.unbindService(connection) }
            serviceBound = false
        }
        _state.value = EraGameState(statusText = "引擎已关闭")
    }

    fun submitInput(
        value: String,
        generation: Long? = _state.value.generation,
    ) {
        val current = _state.value
        if (current.awaiting?.acceptsInput != true) return
        if (generation != null && generation != current.generation) return
        _state.update { it.copy(inputHint = null, statusText = "结算中…") }
        scope.launch {
            client.send(TurnWireCommand.input(value, generation))
        }
    }

    fun submitButton(button: TurnButton) {
        submitInput(button.value, button.generation)
    }

    fun tapLogBackground() {
        if (_state.value.awaiting?.isTapToAdvance == true) {
            submitInput("")
        }
    }

    private fun startInternal(
        game: EraGameInstall,
        packKey: String,
        forceRestart: Boolean,
    ) {
        explicitStop = false
        startJob?.cancel()
        readingJob?.cancel()
        client.close()
        if (forceRestart && serviceBound) {
            runCatching { appContext.unbindService(connection) }
            serviceBound = false
            serviceMessenger = null
        }
        activePackKey = packKey
        _state.value = EraGameState(statusText = "正在准备 ${game.name}…")

        startJob =
            scope.launch {
                try {
                    val zip = game.zipPath ?: packGame(game)
                    pendingPort = pickPort()
                    _state.update { it.copy(statusText = "正在唤起 EraCore 引擎…") }
                    bindEngine(game, zip, pendingPort, forceRestart)
                } catch (error: Exception) {
                    fail(error.message ?: "启动引擎失败")
                }
            }
    }

    private fun connectSocket() {
        readingJob?.cancel()
        val port = pendingPort
        if (port <= 0) return
        readingJob =
            scope.launch {
                _state.update { it.copy(statusText = "等待引擎就绪（端口 $port）…") }
                val reader = client.open(port, HANDSHAKE_TIMEOUT_MS)
                if (reader == null) {
                    if (isActive && !explicitStop && _state.value.error == null) {
                        fail("引擎在 ${HANDSHAKE_TIMEOUT_MS / MS_PER_SECOND} 秒内未就绪")
                    }
                    return@launch
                }
                _state.update { it.copy(connected = true, statusText = "已连接引擎，加载剧本中…") }
                readLoop(reader)
            }
    }

    private fun readLoop(reader: BufferedReader) {
        try {
            while (scope.isActive) {
                val raw = reader.readLine() ?: break
                TurnWireParser.parse(raw)?.let(::handleEvent)
            }
            // 如果是正常切后台断开或显式停止，不报红字错误
            val stillRunning = scope.isActive && !explicitStop && _state.value.error == null
            if (stillRunning && _state.value.awaiting?.isEnded != true) {
                val extra = _state.value.detail?.let { "（$it）" } ?: ""
                fail("与引擎的连接意外关闭$extra")
            }
        } catch (error: Exception) {
            if (scope.isActive && !explicitStop) {
                fail(error.message ?: "与引擎的通讯中断")
            }
        } finally {
            client.close()
            _state.update { it.copy(connected = false) }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun handleEvent(event: HostWireEvent) {
        when (event) {
            is HostWireEvent.Status -> {
                _state.update {
                    it.copy(
                        detail = event.detail ?: it.detail,
                        statusText = if (event.state == "starting") "正在解析游戏脚本…" else it.statusText,
                    )
                }
            }
            is HostWireEvent.Snapshot -> {
                val s = event.snapshot
                _state.update {
                    it.copy(
                        lines = s.lines,
                        bgColor = s.bgColor,
                        bgImages = s.bgImages,
                        awaiting = s.awaiting,
                        generation = s.generation,
                        revision = s.revision,
                        lineCount = s.lines.size,
                        error = null,
                        inputHint = null,
                        statusText = statusTextForAwaiting(s.awaiting),
                    )
                }
            }
            is HostWireEvent.Turn -> {
                val t = event.record
                _state.update { current ->
                    // v13 规则：非终态且 revision <= 当前已应用版本时丢弃；终态必须应用
                    if (!t.awaiting.isEnded && t.revision <= current.revision) {
                        return@update current
                    }
                    val nextLines = if (t.diff != null) applyLineOps(current.lines, t.diff.lineOps) else current.lines
                    val nextBg = t.diff?.bgColor ?: current.bgColor
                    val nextImages = t.diff?.bgImages ?: current.bgImages
                    val structuredErr = t.errorInfo?.formatChain()
                    val fullError =
                        when {
                            t.awaiting.endedTerminal == "Error" ->
                                listOfNotNull(structuredErr, t.error)
                                    .distinct()
                                    .joinToString("\n")
                                    .ifBlank { "引擎运行时异常" }
                            else -> null
                        }
                    val nonFatalHint = if (t.awaiting.endedTerminal != "Error") t.error else null
                    current.copy(
                        lines = nextLines,
                        bgColor = nextBg,
                        bgImages = nextImages,
                        awaiting = t.awaiting,
                        generation = t.generation,
                        revision = t.revision,
                        lineCount = nextLines.size,
                        inputHint = nonFatalHint,
                        error = fullError ?: current.error,
                        statusText = statusTextForAwaiting(t.awaiting),
                    )
                }
            }
            is HostWireEvent.HostError -> fail("[${event.code}] ${event.message}")
            is HostWireEvent.Stopped -> {
                if (event.code != 0 && _state.value.error == null && !explicitStop) {
                    fail("引擎异常退出：${event.reason ?: "exit=${event.code}"}")
                }
            }
        }
    }

    private fun statusTextForAwaiting(awaiting: TurnAwaiting): String =
        when {
            awaiting.endedTerminal == "Quit" -> "游戏已结束"
            awaiting.endedTerminal == "Error" -> "引擎发生异常"
            awaiting.inputType == "Void" -> "等待剧本推进…"
            awaiting.isTapToAdvance -> "点击继续"
            awaiting.acceptsInput -> "轮到你了"
            else -> "运行中"
        }

    @Suppress("CyclomaticComplexMethod")
    private fun onEngineStatus(
        state: String,
        detail: String?,
    ) {
        if (state == EraEngineContract.STATE_RUNNING && detail != null && detail.startsWith(PORT_PREFIX)) {
            val activePort = detail.removePrefix(PORT_PREFIX).toIntOrNull()
            if (activePort != null && activePort > 0 && activePort != pendingPort) {
                pendingPort = activePort
                if (!_state.value.connected) connectSocket()
            }
        }
        _state.update { it.copy(detail = detail ?: it.detail) }
        if (detail != null && detail.startsWith("no_game_installed")) {
            fail("引擎里没有游戏包，请重新导入 ZIP")
            return
        }
        when (state) {
            EraEngineContract.STATE_IMPORTING -> _state.update { it.copy(statusText = "正在解包游戏…") }
            EraEngineContract.STATE_IMPORTED ->
                _state.update {
                    it.copy(statusText = "游戏包已就位", gameDir = detail ?: it.gameDir)
                }
            EraEngineContract.STATE_STARTING -> _state.update { it.copy(statusText = "正在并行加载剧本…") }
            EraEngineContract.STATE_RUNNING -> _state.update { it.copy(statusText = "运行中") }
            EraEngineContract.STATE_STOPPED -> {
                if (detail != null && detail.contains("exit=1")) {
                    fail("引擎启动失败：$detail")
                } else {
                    _state.update { it.copy(statusText = "引擎已停止") }
                }
            }
            EraEngineContract.STATE_ERROR -> fail("引擎启动失败：${detail ?: "未知错误"}")
            else -> Unit
        }
    }

    private fun packGame(game: EraGameInstall): File =
        packager.pack(
            game = game,
            onProgress = { done, total ->
                _state.update { it.copy(statusText = "正在打包 $done/$total 个文件…") }
            },
        )

    private fun bindEngine(
        game: EraGameInstall,
        zip: File,
        port: Int,
        forceRestart: Boolean,
    ) {
        val messenger = Messenger(handler)
        val merged = BuildConfig.ERA_MERGED
        val descriptor = if (merged) null else ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY)
        val intent =
            Intent().apply {
                component = EraEngineContract.componentFor(appContext)
                putExtra(EraEngineContract.EXTRA_REPLY_TO, messenger)
                putExtra(EraEngineContract.EXTRA_PORT, port)
                putExtra(EraEngineContract.EXTRA_GAME_NAME, game.name)
                putExtra(EraEngineContract.EXTRA_PACK_VERSION, packager.versionKey(game))
                putExtra("forceRestart", forceRestart)
                if (merged) {
                    putExtra(EraEngineContract.EXTRA_ZIP_PATH, zip.absolutePath)
                } else if (descriptor != null) {
                    putExtra(EraEngineContract.EXTRA_ZIP_FD, descriptor)
                }
            }
        val target = EraEngineContract.componentFor(appContext)
        val installed = runCatching { appContext.packageManager.getPackageInfo(target.packageName, 0) }.isSuccess
        var bound = false
        val bindError =
            runCatching {
                bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.exceptionOrNull()
        bound = bound && bindError == null
        runCatching { descriptor?.close() }
        if (!installed) {
            fail("引擎组件未安装（${target.packageName}）")
        } else if (bindError != null) {
            fail("绑定引擎被拒：${bindError.javaClass.simpleName} ${bindError.message ?: ""}".trim())
        } else if (!bound) {
            fail("引擎组件存在但绑定失败（${target.flattenToShortString()}）")
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message, statusText = "异常：$message") }
    }

    private fun pickPort(): Int =
        runCatching {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.getOrDefault(FALLBACK_PORT)

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 180_000L
        const val FALLBACK_PORT = 5177
        const val PORT_PREFIX = "port="
        const val MS_PER_SECOND = 1000L
    }
}
