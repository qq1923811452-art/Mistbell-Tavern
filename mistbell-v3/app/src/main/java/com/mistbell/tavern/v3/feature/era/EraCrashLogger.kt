package com.mistbell.tavern.v3.feature.era

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把未捕获异常写进外部私有目录的 era-crash.log。
 *
 * 引擎跑在 :era 子进程、崩溃不一定留下可见痕迹；加上真机没有 adb 时，
 * 这是唯一能拿到 Java 层崩溃栈的手段。
 */
internal object EraCrashLogger {
    const val FILE_NAME = "era-crash.log"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = app.getExternalFilesDir(null) ?: app.filesDir
                File(dir, FILE_NAME).appendText(
                    buildString {
                        appendLine("=== ${System.currentTimeMillis()} thread=${thread.name}")
                        appendLine(Log.getStackTraceString(error))
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
