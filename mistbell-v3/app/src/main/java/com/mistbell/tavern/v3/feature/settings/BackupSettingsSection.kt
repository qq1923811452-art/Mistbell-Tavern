package com.mistbell.tavern.v3.feature.settings

import android.app.Activity
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mistbell.tavern.v3.feature.chat.ChatBackupCoordinator
import com.mistbell.tavern.v3.feature.chat.ChatBackupSettingsSection

/** Backup and restore entry point for the settings page. */
@Suppress("FunctionNaming")
@Composable
fun BackupSettingsSection(onRestored: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreName by remember { mutableStateOf("所选备份") }
    val coordinator = remember(context, scope) {
        ChatBackupCoordinator(
            context = context,
            scope = scope,
            onStatus = { status = it },
            onRestored = {
                onRestored()
                activity?.recreate()
            },
        )
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) coordinator.export(uri) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            restoreName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull() ?: uri.lastPathSegment ?: "所选备份"
            pendingRestore = uri
        }
    }

    ChatBackupSettingsSection(
        onExport = { exportLauncher.launch("mistbell-backup.zip") },
        onRestore = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) },
        canRestore = true,
        statusMessage = status,
    )
    if (pendingRestore != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复备份？") },
            text = {
                Text(
                    "将恢复：$restoreName\n" +
                        "当前会话、设置和记忆数据会被备份内容替换，恢复后应用会重新加载。",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        pendingRestore?.let(coordinator::restore)
                        pendingRestore = null
                    },
                ) { Text("恢复") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingRestore = null }) {
                    Text("取消")
                }
            },
        )
    }
}
