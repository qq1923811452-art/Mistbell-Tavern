package com.mistbell.tavern.android.util

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

/**
 * 原子写文件：先写临时文件，成功后再替换目标。
 *
 * 直接用 `File.writeBytes()` 是「先截断再写」—— 写入过程中崩溃/被杀/断电会留下**半截文件**，
 * 且原内容已经丢失。导入资材、背景图这类落盘都应走这里。
 *
 * 与 `BackupManager` 内部那份实现语义一致（失败时回滚并原样抛出）。
 */
object AtomicWrite {
    fun bytes(
        target: File,
        bytes: ByteArray,
    ) {
        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null
        // 用 runCatching 收敛失败路径：既避免 TooGenericExceptionCaught（catch Exception），
        // 也避免写成多个 catch 触发 ThrowsCount 上限。失败原因原样抛出，语义不变。
        val failure =
            runCatching {
                output = atomicFile.startWrite()
                output.write(bytes)
                atomicFile.finishWrite(output)
            }.exceptionOrNull()
        if (failure != null) {
            output?.let { runCatching { atomicFile.failWrite(it) } }
            throw failure
        }
    }
}
