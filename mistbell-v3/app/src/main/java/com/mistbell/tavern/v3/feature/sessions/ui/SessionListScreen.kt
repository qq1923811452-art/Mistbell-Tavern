package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
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

@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
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
    characters: List<GroupCandidate> = emptyList(),
    onCreateGroup: (participantIds: List<String>) -> Unit = {},
) {
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ 返回") }
                },
                actions = {
                    if (characters.isNotEmpty()) {
                        TextButton(onClick = { showGroupDialog = true }) { Text("新建群聊") }
                    }
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
        if (showGroupDialog) {
            GroupCreateDialog(
                characters = characters,
                onDismiss = { showGroupDialog = false },
                onConfirm = { ids ->
                    showGroupDialog = false
                    onCreateGroup(ids)
                },
            )
        }
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

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun GroupCreateDialog(
    characters: List<GroupCandidate>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建群聊") },
        text = {
            LazyColumn {
                items(characters, key = { it.id }) { candidate ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = candidate.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) {
                                    (selected + candidate.id).take(MAX_GROUP_MEMBERS).toSet()
                                } else {
                                    selected - candidate.id
                                }
                            },
                        )
                        Text(candidate.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.size in MIN_GROUP_MEMBERS..MAX_GROUP_MEMBERS,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private const val MIN_GROUP_MEMBERS = 2
private const val MAX_GROUP_MEMBERS = 6
