package com.mistbell.tavern.v3.feature.settings

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
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val coordinator = remember(context, scope) {
        ChatBackupCoordinator(
            context = context,
            scope = scope,
            onStatus = { status = it },
            onRestored = onRestored,
        )
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) coordinator.export(uri) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingRestore = uri }

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
            text = { Text("当前数据将被备份内容替换，恢复后应用会重新加载。") },
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
