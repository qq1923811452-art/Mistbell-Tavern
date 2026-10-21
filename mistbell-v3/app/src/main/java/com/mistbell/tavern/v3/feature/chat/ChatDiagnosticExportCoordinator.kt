package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.net.Uri
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatDiagnosticExportCoordinator(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val diagnosticStore: DiagnosticLogStore,
    private val setStatus: (String) -> Unit,
) {
    fun export(uri: Uri) {
        coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use(diagnosticStore::exportTo)
                        ?: error("无法创建日志文件。")
                }
            }
            setStatus(
                if (result.isSuccess) {
                    "诊断日志已导出。日志不主动包含聊天正文或 API 密钥。"
                } else {
                    "诊断日志导出失败：${result.exceptionOrNull()?.message?.take(100) ?: "文件写入失败"}"
                },
            )
        }
    }
}
