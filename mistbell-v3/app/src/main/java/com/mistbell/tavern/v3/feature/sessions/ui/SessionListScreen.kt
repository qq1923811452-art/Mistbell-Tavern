@file:Suppress("FunctionNaming")

package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.feature.sessions.ChatSession

private val LIST_HORIZONTAL_PADDING = 16.dp
private val LIST_TOP_PADDING = 8.dp
private val LIST_BOTTOM_PADDING = 112.dp
private val LIST_ITEM_SPACING = 10.dp
private val FAB_END_PADDING = 20.dp
private val FAB_BOTTOM_PADDING = 22.dp
private val GROUP_DIALOG_MAX_HEIGHT = 320.dp
private val GROUP_ITEM_SPACING = 4.dp
private val GROUP_ITEM_VERTICAL_PADDING = 2.dp

private const val MIN_GROUP_MEMBERS = 2
private const val MAX_GROUP_MEMBERS = 6

@Suppress("LongMethod", "LongParameterList")
@Composable
fun SessionListScreen(
    sessions: List<ChatSession>,
    selectedSessionId: String?,
    onSessionClick: (String) -> Unit,
    onCreateSession: () -> Unit,
    onRenameSession: (id: String, title: String) -> Unit,
    onDeleteSession: (id: String) -> Unit,
    onTogglePin: (id: String, pinned: Boolean) -> Unit,
    characters: List<GroupCandidate> = emptyList(),
    onCreateGroup: (participantIds: List<String>) -> Unit = {},
) {
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }


    MaterialTheme(colorScheme = TAVERN_DARK_COLOR_SCHEME) {
        Box(modifier = Modifier.fillMaxSize()) {
            SessionListBackdrop()
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    SessionListHeader(
                        showGroupAction = characters.isNotEmpty(),
                        onOpenGroupDialog = { showGroupDialog = true },
                    )
                },
            ) { innerPadding ->
                SessionListContent(
                    sessions = sessions,
                    selectedSessionId = selectedSessionId,
                    characters = characters,
                    contentPadding = innerPadding,
                    onSessionClick = onSessionClick,
                    onCreateSession = onCreateSession,
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it },
                    onTogglePin = onTogglePin,
                )
            }
            NewSessionFab(
                onClick = onCreateSession,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = FAB_END_PADDING, bottom = FAB_BOTTOM_PADDING),
            )
        }
        SessionMutationDialogs(
            renameTarget = renameTarget,
            deleteTarget = deleteTarget,
            showGroupDialog = showGroupDialog,
            characters = characters,
            onDismissRename = { renameTarget = null },
            onConfirmRename = { session, title ->
                onRenameSession(session.id, title)
                renameTarget = null
            },
            onDismissDelete = { deleteTarget = null },
            onConfirmDelete = { session ->
                onDeleteSession(session.id)
                deleteTarget = null
            },
            onDismissGroup = { showGroupDialog = false },
            onConfirmGroup = { participantIds ->
                showGroupDialog = false
                onCreateGroup(participantIds)
            },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun SessionListContent(
    sessions: List<ChatSession>,
    selectedSessionId: String?,
    characters: List<GroupCandidate>,
    contentPadding: PaddingValues,
    onSessionClick: (String) -> Unit,
    onCreateSession: () -> Unit,
    onRename: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onTogglePin: (id: String, pinned: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (sessions.isEmpty()) {
            SessionEmptyState(
                onCreateSession = onCreateSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = LIST_HORIZONTAL_PADDING,
                    end = LIST_HORIZONTAL_PADDING,
                    top = LIST_TOP_PADDING,
                    bottom = LIST_BOTTOM_PADDING,
                ),
                verticalArrangement = Arrangement.spacedBy(LIST_ITEM_SPACING),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == selectedSessionId,
                        onClick = { onSessionClick(session.id) },
                        onRename = { onRename(session) },
                        onDelete = { onDelete(session) },
                        onTogglePin = { pinned -> onTogglePin(session.id, pinned) },
                        characters = characters,
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun SessionMutationDialogs(
    renameTarget: ChatSession?,
    deleteTarget: ChatSession?,
    showGroupDialog: Boolean,
    characters: List<GroupCandidate>,
    onDismissRename: () -> Unit,
    onConfirmRename: (ChatSession, String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (ChatSession) -> Unit,
    onDismissGroup: () -> Unit,
    onConfirmGroup: (List<String>) -> Unit,
) {
    renameTarget?.let { session ->
        SessionRenameDialog(
            initialTitle = session.title,
            onDismiss = onDismissRename,
            onConfirm = { title -> onConfirmRename(session, title) },
        )
    }
    deleteTarget?.let { session ->
        DeleteSessionDialog(
            session = session,
            onDismiss = onDismissDelete,
            onConfirm = { onConfirmDelete(session) },
        )
    }
    if (showGroupDialog) {
        GroupCreateDialog(
            characters = characters,
            onDismiss = onDismissGroup,
            onConfirm = onConfirmGroup,
        )
    }
}

@Composable
private fun DeleteSessionDialog(
    session: ChatSession,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除对话？") },
        text = { Text("删除“${session.title}”后将无法从会话列表恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Suppress("LongParameterList")
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = GROUP_DIALOG_MAX_HEIGHT),
                verticalArrangement = Arrangement.spacedBy(GROUP_ITEM_SPACING),
            ) {
                items(characters, key = { it.id }) { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = GROUP_ITEM_VERTICAL_PADDING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
