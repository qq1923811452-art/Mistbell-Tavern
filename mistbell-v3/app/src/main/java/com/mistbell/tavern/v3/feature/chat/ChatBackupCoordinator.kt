package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.mistbell.tavern.v3.core.backup.BackupService
import com.mistbell.tavern.v3.feature.backup.AppBackupDatabaseLifecycle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bridges the SAF document pickers to [BackupService]. All file work runs on the IO dispatcher;
 * user-visible messages are posted back to the main thread.
 */
class ChatBackupCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onRestored: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val service by lazy { BackupService(context, databaseLifecycle = AppBackupDatabaseLifecycle()) }

    @Suppress("TooGenericExceptionCaught")
    fun export(target: Uri) {
        scope.launch(Dispatchers.IO) {
            val temporary = File(context.cacheDir, "mistbell-backup-${System.currentTimeMillis()}.zip")
            try {
                val result = service.export(temporary)
                val output = context.contentResolver.openOutputStream(target)
                    ?: error("目标文件不可写")
                output.use { sink ->
                    temporary.inputStream().use { source -> source.copyTo(sink) }
                }
                publish("已导出 ${result.manifest.entryCount} 项，${result.manifest.totalBytes / BYTES_PER_KILOBYTE} KB")
            } catch (error: Exception) {
                publish("导出失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                temporary.delete()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun restore(source: Uri) {
        scope.launch(Dispatchers.IO) {
            val temporary = File(context.cacheDir, "mistbell-restore-${System.currentTimeMillis()}.zip")
            try {
                val input = context.contentResolver.openInputStream(source)
                    ?: error("备份文件不可读")
                input.use { stream ->
                    temporary.outputStream().use { sink ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_BACKUP_BYTES) {
                                "备份文件超过 512 MiB 上限。"
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                val preview = service.restore(temporary)
                publish("已恢复 ${preview.manifest.entryCount} 项，正在重新加载")
                mainHandler.post { onRestored() }
            } catch (error: Exception) {
                publish("恢复失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                temporary.delete()
            }
        }
    }

    private fun publish(message: String) {
        mainHandler.post { onStatus(message) }
    }

    companion object {
        private const val BYTES_PER_KILOBYTE = 1024L
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_BACKUP_BYTES = 512L * 1024 * 1024
    }
}
