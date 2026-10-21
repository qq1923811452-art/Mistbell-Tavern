package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.feature.sessions.ChatSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<ChatSession>,
    selectedSessionId: String?,
    onSessionClick: (String) -> Unit,
    onCreateSession: () -> Unit,
    onRenameSession: (id: String, title: String) -> Unit,
    onDeleteSession: (id: String) -> Unit,
    onTogglePin: (id: String, pinned: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ 返回") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateSession,
                text = { Text("新建对话") },
                icon = { Text("+") },
            )
        },
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            SessionEmptyState(
                onCreateSession = onCreateSession,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == selectedSessionId,
                        onClick = { onSessionClick(session.id) },
                        onRename = { renameTarget = session },
                        onDelete = { deleteTarget = session },
                        onTogglePin = { pinned -> onTogglePin(session.id, pinned) },
                    )
                }
            }
        }
    }

    renameTarget?.let { session ->
        SessionRenameDialog(
            initialTitle = session.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                onRenameSession(session.id, title)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话？") },
            text = { Text("删除“${session.title}”后将无法从会话列表恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
