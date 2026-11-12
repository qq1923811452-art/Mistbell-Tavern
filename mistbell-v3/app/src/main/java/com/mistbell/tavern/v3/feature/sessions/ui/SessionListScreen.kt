@file:Suppress("FunctionNaming")

package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.R
import com.mistbell.tavern.v3.feature.sessions.ChatSession
import com.mistbell.tavern.v3.ui.CharacterAvatar
import com.mistbell.tavern.v3.ui.TavernBackground
import com.mistbell.tavern.v3.ui.tavern.TavernCheck
import com.mistbell.tavern.v3.ui.tavern.TavernDialog
import com.mistbell.tavern.v3.ui.tavern.TavernDialogActions
import com.mistbell.tavern.v3.ui.tavern.TavernDialogTitle
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import com.mistbell.tavern.v3.ui.tavern.rememberTavernListState

private val LIST_HORIZONTAL_PADDING = 16.dp
private val LIST_TOP_PADDING = 4.dp
private val LIST_BOTTOM_PADDING = 24.dp
private val LIST_ITEM_SPACING = 10.dp
private val GROUP_DIALOG_MAX_HEIGHT = 320.dp
private val GROUP_AVATAR_SIZE = 38.dp

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
        // The painted backdrop replaces the flat gradient: the scrim is lighter here than on the
        // chat screen because the list is mostly cards, which already carry their own contrast.
        TavernBackground(
            drawable = R.drawable.bg_sessions,
            topAlpha = 0.30f,
            middleAlpha = 0.26f,
            bottomAlpha = 0.72f,
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                // The tab bar is drawn by the host below this surface and already reserves the
                // system navigation inset. Reserving it again leaves a dead band under the list.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    SessionListHeader(
                        showGroupAction = characters.isNotEmpty(),
                        onCreateSession = onCreateSession,
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
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        if (sessions.isEmpty()) {
            SessionEmptyState(
                onCreateSession = onCreateSession,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
        } else {
            LazyColumn(
                state = rememberTavernListState("session_list"),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentPadding =
                    PaddingValues(
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
    TavernDialog(onDismissRequest = onDismiss) {
        TavernDialogTitle(title = "新建群聊", subtitle = "选 2–6 位角色，同一条时间线里一起回应")
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = GROUP_DIALOG_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(characters, key = { it.id }) { candidate ->
                val checked = candidate.id in selected
                GroupCandidateRow(
                    candidate = candidate,
                    checked = checked,
                    onToggle = {
                        selected =
                            if (checked) {
                                selected - candidate.id
                            } else {
                                (selected + candidate.id).take(MAX_GROUP_MEMBERS).toSet()
                            }
                    },
                )
            }
        }
        TavernDialogActions(
            confirmLabel = "创建群聊",
            onConfirm = { onConfirm(selected.toList()) },
            onDismiss = onDismiss,
            confirmEnabled = selected.size in MIN_GROUP_MEMBERS..MAX_GROUP_MEMBERS,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun GroupCandidateRow(
    candidate: GroupCandidate,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.CardSmall)
                .background(if (checked) TavernPalette.Purple.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(role = androidx.compose.ui.semantics.Role.Checkbox, onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterAvatar(
            name = candidate.name,
            avatarFileName = candidate.avatarFileName,
            size = GROUP_AVATAR_SIZE,
        )
        Text(
            text = candidate.name,
            color = TavernPalette.TextPrimary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TavernCheck(checked = checked)
    }
}
