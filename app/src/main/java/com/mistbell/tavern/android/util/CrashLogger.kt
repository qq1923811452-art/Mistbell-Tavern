package com.mistbell.tavern.android.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.mistbell.tavern.android.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃 / 运行日志记录器
 *
 * 在应用启动时通过 [init] 注册全局未捕获异常处理器，将崩溃堆栈与设备信息
 * 写入应用私有目录（不需要存储权限，数据不离开设备，符合本地隐私定位）。
 * 用户可在设置中查看、清除或导出日志，导出时通过 FileProvider 分享文件。
 *
 * 除崩溃外，预览/导出时还会按需（不持续后台运行）通过 [dumpLogcat] 抓取**本应用
 * 进程**的最近 logcat（读取自身进程无需任何权限），用于诊断「点击失效、页面没升起」
 * 等不会崩溃的问题——这类问题的线索通常是导航/点击的 Log.d 面包屑或被 try/catch
 * 吞掉的 Log.e。logcat 数据仅存在于系统环形缓冲区，按需读取后拼接进导出报告，
 * 不额外落盘。
 *
 * 隐私说明：崩溃日志记录异常堆栈与设备/版本信息，不会记录 API Key（仅存在于请求头，
 * 不出现在堆栈或异常消息中）。logcat 抓取时按 [CONTENT_TAGS] 黑名单整条丢弃已知会
 * 打印聊天/记忆内容或回显服务器响应体的 tag，避免聊天内容进入导出文件。异常消息可能
 * 包含服务器返回的错误信息，因此不应在异常消息中拼接聊天内容（参见
 * LlmClient/OpenAIEmbeddingService 的处理）。
 */
object CrashLogger {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "crash_log.txt"
    private const val EXPORT_DIR = "exports"

    // 日志文件大小上限（字节），超出后只保留最近的内容，避免无限增长
    private const val MAX_LOG_SIZE = 256 * 1024

    // logcat 抓取时整条丢弃的 tag 黑名单：这些 tag 已知会打印聊天/记忆内容，
    // 或回显服务器响应体，可能含聊天片段，因此从导出报告中排除以保护隐私。
    private val CONTENT_TAGS =
        setOf(
            "ChatViewModel",
            "MemoryExtractionService",
            "ChatSettings",
            "VectorMemoryService",
            "OpenAIEmbedding",
            "LlmClient",
            // OkHttp 默认 tag：BODY 级日志会回显完整请求头（含 Authorization 密钥）与请求体
            "okhttp.OkHttpClient",
            "CharacterImporter",
            "CharacterEditor",
            "SecureStore",
            // `Throwable.printStackTrace()` 写到 System.err，logcat 会以这两个 tag 记录。
            // 它们不带上文里的任何业务 tag，因此**不受上面的黑名单约束**——不显式排除的话，
            // 各处 printStackTrace() 的完整堆栈（可能含聊天/记忆片段）会直接进入导出报告。
            "System.err",
            "System.out",
        )

    // threadtime 格式日志首行：日期 时间 PID TID 优先级 TAG: 消息
    // 例：06-24 12:00:00.123  1234  1250 D AppNavigation: 导航到...
    // 用于解析 tag；多行堆栈的后续行无此前缀，沿用上一条 entry 的丢弃决定。
    private val LOGCAT_HEADER =
        Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+):""")

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // 串行化崩溃写入，避免多线程同时崩溃时交错写/读到半成品
    private val lock = Any()

    @Volatile
    private var installed = false

    /**
     * 注册全局未捕获异常处理器。应在 Application.onCreate 中尽早调用。
     * 仅设置 handler，不做任何重量级初始化。幂等：重复调用不会重复注册。
     */
    fun init(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // 记录失败不能影响系统默认的崩溃流程
            }
            // 交还给系统默认处理器，保持正常的崩溃弹窗/上报行为
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logDir(context: Context): File = File(context.filesDir, LOG_DIR).apply { if (!exists()) mkdirs() }

    private fun logFile(context: Context): File = File(logDir(context), LOG_FILE)

    private fun writeCrash(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        val sb = StringBuilder()
        sb.append("========== 崩溃记录 ==========\n")
        sb.append("时间: ${timeFormat.format(Date(System.currentTimeMillis()))}\n")
        sb.append("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("线程: ${thread.name}\n")
        sb.append("异常: ${throwable.javaClass.name}: ${throwable.message}\n")
        sb.append("堆栈:\n")
        sb.append(android.util.Log.getStackTraceString(throwable))
        sb.append("\n\n")

        val file = logFile(context)
        synchronized(lock) {
            file.appendText(sb.toString(), Charsets.UTF_8)
            // 超过上限时按字节截断，保留尾部最近内容
            if (file.length() > MAX_LOG_SIZE) {
                val bytes = file.readBytes()
                if (bytes.size > MAX_LOG_SIZE) {
                    // 从尾部取 MAX_LOG_SIZE 字节，跳到下一个合法 UTF-8 起始字节避免乱码
                    var start = bytes.size - MAX_LOG_SIZE
                    while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
                    file.writeBytes(bytes.copyOfRange(start, bytes.size))
                }
            }
        }
    }

    /** 是否存在崩溃日志 */
    fun hasLogs(context: Context): Boolean {
        val file = logFile(context)
        return file.exists() && file.length() > 0
    }

    /** 读取全部日志内容（用于界面预览） */
    fun readLogs(context: Context): String {
        val file = logFile(context)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    /**
     * 按需抓取**本应用进程**最近的 logcat（读取自身进程无需任何权限）。
     * 不持续后台运行，仅在预览/导出时调用一次。logcat 数据来自系统环形缓冲区，
     * 抓取后按 [CONTENT_TAGS] 黑名单整条丢弃含聊天/记忆内容的行，不额外落盘。
     *
     * 解析 threadtime 首行的 tag 做过滤；多行堆栈的后续行无前缀，沿用上一条
     * entry 的丢弃决定（属于被丢 tag 的堆栈整体丢弃）。结果按 [MAX_LOG_SIZE]
     * 截断尾部最近内容。
     *
     * @return 过滤后的 logcat 文本；无内容或抓取失败时返回空串（绝不抛出）。
     */
    fun dumpLogcat(): String {
        return try {
            val pid = Process.myPid().toString()
            // -d 一次性导出后退出；-v threadtime 带 PID/TID/优先级/tag；
            // --pid 仅本进程（API 24+，本应用 minSdk 26 满足）。
            val process =
                ProcessBuilder(
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "--pid",
                    pid,
                ).redirectErrorStream(true).start()

            val filtered = StringBuilder()
            // 当前 entry 是否属于被丢弃的 tag：决定无前缀续行（堆栈）的去留
            var dropping = false
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    val match = LOGCAT_HEADER.find(line)
                    if (match != null) {
                        val tag = match.groupValues[1].trim()
                        dropping = CONTENT_TAGS.contains(tag)
                    }
                    // 无前缀续行：沿用上一条 entry 的决定
                    if (!dropping) {
                        filtered.append(line).append('\n')
                    }
                }
            }
            process.waitFor()

            // 按字节截断尾部，跳到下一个合法 UTF-8 起始字节避免乱码
            val bytes = filtered.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_LOG_SIZE) {
                var start = bytes.size - MAX_LOG_SIZE
                while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
                String(bytes.copyOfRange(start, bytes.size), Charsets.UTF_8)
            } else {
                filtered.toString()
            }
        } catch (e: Exception) {
            android.util.Log.e("CrashLogger", "抓取 logcat 失败: ${e.message}", e)
            ""
        }
    }

    /**
     * 构建完整诊断报告：崩溃记录 + 过滤后的运行日志（logcat）。
     * 供预览与导出复用。可能阻塞（logcat 子进程 + IO），必须在后台线程调用。
     */
    fun buildDiagnosticReport(context: Context): String {
        val sb = StringBuilder()
        sb.append("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}  系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("导出时间: ${timeFormat.format(Date(System.currentTimeMillis()))}\n\n")

        val crash = readLogs(context)
        if (crash.isNotBlank()) {
            sb.append(crash)
            if (!crash.endsWith("\n")) sb.append('\n')
        } else {
            sb.append("（无崩溃记录）\n")
        }

        sb.append("\n========== 运行日志（本进程最近 logcat，已过滤聊天内容） ==========\n")
        val logcat = dumpLogcat()
        sb.append(if (logcat.isNotBlank()) logcat else "（无运行日志，或当前系统不支持读取）\n")
        return sb.toString()
    }

    /** 清除日志 */
    fun clearLogs(context: Context) {
        val file = logFile(context)
        if (file.exists()) file.delete()
    }

    /**
     * 将已构建好的诊断报告写入可分享目录并返回 FileProvider URI。
     * 复用 res/xml/file_paths.xml 中已配置的 cache-path "exports/"。
     * 入参为 [buildDiagnosticReport] 的结果，避免在导出时重复抓取 logcat。
     * @return 可用于分享的 URI，内容为空或写入失败时返回 null
     */
    fun exportReport(
        context: Context,
        content: String,
    ): Uri? {
        if (content.isBlank()) return null
        return try {
            val exportDir = File(context.cacheDir, EXPORT_DIR).apply { if (!exists()) mkdirs() }
            val fileName = "tavern_log_${System.currentTimeMillis()}.txt"
            val exportFile = File(exportDir, fileName)
            exportFile.writeText(content, Charsets.UTF_8)
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                exportFile,
            )
        } catch (e: Exception) {
            android.util.Log.e("CrashLogger", "导出日志失败: ${e.message}", e)
            null
        }
    }

    /** 创建分享日志文件的 Intent */
    fun createShareIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mistbell Tavern 诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
