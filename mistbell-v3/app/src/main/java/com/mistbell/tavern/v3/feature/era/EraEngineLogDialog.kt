@file:Suppress("FunctionNaming", "MagicNumber")

package com.mistbell.tavern.v3.feature.era

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import java.io.File

private const val LOG_TAIL_LINES = 200
private val ERA_LOG_MAX_HEIGHT = 420.dp

/** 读引擎写在外部私有目录的启动日志；没有 logcat 时靠它定位。 */
internal fun readEngineLog(context: Context): List<String> {
    val file = File(context.getExternalFilesDir(null), "era-engine.log")
    if (!file.isFile) return listOf("没有找到日志文件：${file.absolutePath}")
    return runCatching { file.readLines().takeLast(LOG_TAIL_LINES) }
        .getOrElse { listOf("读取失败：${it.message}") }
}

@Composable
internal fun EraLogDialog(
    lines: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("引擎日志") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = ERA_LOG_MAX_HEIGHT)) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        color = TavernPalette.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        },
    )
}
