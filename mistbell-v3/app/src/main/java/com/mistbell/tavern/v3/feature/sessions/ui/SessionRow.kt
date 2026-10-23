@file:Suppress("FunctionNaming")

package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.feature.sessions.ChatSession
import com.mistbell.tavern.v3.feature.sessions.SessionKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val SESSION_CARD_SHAPE = RoundedCornerShape(20.dp)
private val SESSION_CARD_BRUSH = Brush.verticalGradient(
    colors = listOf(TAVERN_CARD_TOP, TAVERN_CARD_BOTTOM),
)
private val SESSION_CARD_SELECTED_BRUSH = Brush.verticalGradient(
    colors = listOf(TAVERN_CARD_SELECTED_TOP, TAVERN_CARD_SELECTED_BOTTOM),
)
private val SESSION_AVATAR_BRUSH = Brush.linearGradient(
    colors = listOf(TAVERN_AVATAR_START, TAVERN_AVATAR_END),
)
private val SESSION_CARD_PADDING = 14.dp
private val SESSION_CARD_GAP = 12.dp
private val SESSION_AVATAR_SIZE = 52.dp
private val CARD_BORDER_WIDTH = 1.dp
private val CARD_SELECTED_BORDER_WIDTH = 1.5.dp
private val CARD_LINE_GAP = 4.dp
private val CARD_BADGE_GAP = 6.dp
private val CARD_TITLE_SIZE = 16.sp
private val CARD_ROLE_SIZE = 12.sp
private val CARD_PREVIEW_SIZE = 13.sp
private val CARD_TIME_SIZE = 11.sp
private val PIN_BADGE_HORIZONTAL_PADDING = 6.dp
private val PIN_BADGE_VERTICAL_PADDING = 2.dp
private val PIN_BADGE_TEXT_SIZE = 9.sp
private val AVATAR_INITIAL_SIZE = 20.sp
private val AVATAR_BORDER_WIDTH = 1.dp
private val MENU_TOUCH_SIZE = 40.dp
private val MENU_GLYPH_SIZE = 22.sp

private const val DEFAULT_SESSION_TITLE = "新对话"
private const val EMPTY_PREVIEW = "还没有消息，点开开始第一句吧"
private const val DEFAULT_ROLE_ID = "default-character"
private const val GROUP_ROLE_NAME = "群聊"
private const val UNSELECTED_ROLE_NAME = "未选择角色"
private const val UNKNOWN_ROLE_NAME = "未知角色"
private const val AVATAR_FALLBACK_INITIAL = "·"
private const val TODAY_TIME_PATTERN = "HH:mm"
private const val DATE_PATTERN = "MM/dd"
private const val MENU_CONTENT_DESCRIPTION = "更多操作"

private data class SessionRowUi(
    val title: String,
    val roleName: String,
    val preview: String,
    val timeLabel: String,
)

private data class SessionRowActions(
    val onClick: () -> Unit,
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
    val onTogglePin: (Boolean) -> Unit,
)

@Suppress("LongParameterList")
@Composable
fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
    characters: List<GroupCandidate> = emptyList(),
) {
    val timeLabel = remember(session.updatedAt) { formatSessionTimestamp(session.updatedAt) }
    val ui = SessionRowUi(
        title = session.title.ifBlank { DEFAULT_SESSION_TITLE },
        roleName = session.roleDisplayName(characters),
        preview = session.preview.ifBlank { EMPTY_PREVIEW },
        timeLabel = timeLabel,
    )
    SessionCard(
        session = session,
        ui = ui,
        selected = selected,
        actions = SessionRowActions(
            onClick = onClick,
            onRename = onRename,
            onDelete = onDelete,
            onTogglePin = onTogglePin,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: ChatSession,
    ui: SessionRowUi,
    selected: Boolean,
    actions: SessionRowActions,
) {
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SESSION_CARD_SHAPE)
            .background(
                brush = if (selected) SESSION_CARD_SELECTED_BRUSH else SESSION_CARD_BRUSH,
                shape = SESSION_CARD_SHAPE,
            )
            .border(
                width = if (selected) CARD_SELECTED_BORDER_WIDTH else CARD_BORDER_WIDTH,
                color = if (selected) TAVERN_PURPLE else TAVERN_CARD_BORDER,
                shape = SESSION_CARD_SHAPE,
            )
            .combinedClickable(
                onClick = actions.onClick,
                onLongClick = { menuExpanded = true },
            )
            .padding(SESSION_CARD_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SESSION_CARD_GAP),
        ) {
            SessionAvatar(name = ui.roleName)
            SessionCardDetails(
                ui = ui,
                pinned = session.pinned,
                modifier = Modifier.weight(1f),
            )
            SessionMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                pinned = session.pinned,
                actions = actions,
            )
        }
    }
}

@Composable
private fun SessionCardDetails(
    ui: SessionRowUi,
    pinned: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ui.title,
                modifier = Modifier.weight(1f),
                color = TAVERN_TEXT_PRIMARY,
                fontSize = CARD_TITLE_SIZE,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pinned) {
                Spacer(modifier = Modifier.width(CARD_BADGE_GAP))
                PinnedBadge()
            }
            if (ui.timeLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.width(CARD_BADGE_GAP))
                Text(
                    text = ui.timeLabel,
                    color = TAVERN_TEXT_TERTIARY,
                    fontSize = CARD_TIME_SIZE,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(CARD_LINE_GAP))
        Text(
            text = ui.roleName,
            color = TAVERN_TEXT_SECONDARY,
            fontSize = CARD_ROLE_SIZE,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(CARD_LINE_GAP))
        Text(
            text = ui.preview,
            color = TAVERN_TEXT_TERTIARY,
            fontSize = CARD_PREVIEW_SIZE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionAvatar(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: AVATAR_FALLBACK_INITIAL
    Box(
        modifier = Modifier
            .size(SESSION_AVATAR_SIZE)
            .clip(CircleShape)
            .background(SESSION_AVATAR_BRUSH)
            .border(AVATAR_BORDER_WIDTH, TAVERN_AVATAR_BORDER, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = AVATAR_INITIAL_SIZE,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PinnedBadge() {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(TAVERN_PIN_BADGE_BG)
            .padding(
                horizontal = PIN_BADGE_HORIZONTAL_PADDING,
                vertical = PIN_BADGE_VERTICAL_PADDING,
            ),
    ) {
        Text(
            text = "置顶",
            color = TAVERN_PURPLE_SOFT,
            fontSize = PIN_BADGE_TEXT_SIZE,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SessionMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    pinned: Boolean,
    actions: SessionRowActions,
) {
    Box {
        Box(
            modifier = Modifier
                .size(MENU_TOUCH_SIZE)
                .clip(CircleShape)
                .clickable(
                    onClickLabel = MENU_CONTENT_DESCRIPTION,
                    role = Role.Button,
                    onClick = { onExpandedChange(true) },
                )
                .semantics { contentDescription = MENU_CONTENT_DESCRIPTION },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⋮",
                color = TAVERN_TEXT_SECONDARY,
                fontSize = MENU_GLYPH_SIZE,
                fontWeight = FontWeight.Bold,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = {
                    onExpandedChange(false)
                    actions.onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(if (pinned) "取消置顶" else "置顶") },
                onClick = {
                    onExpandedChange(false)
                    actions.onTogglePin(!pinned)
                },
            )
            DropdownMenuItem(
                text = { Text("删除", color = TAVERN_DANGER) },
                onClick = {
                    onExpandedChange(false)
                    actions.onDelete()
                },
            )
        }
    }
}

private fun ChatSession.roleDisplayName(characters: List<GroupCandidate>): String {
    if (kind == SessionKind.GROUP) {
        val names = participants.map { it.name }.filter { it.isNotBlank() }
        return if (names.isEmpty()) {
            GROUP_ROLE_NAME
        } else {
            "$GROUP_ROLE_NAME · ${names.joinToString("、")}"
        }
    }
    val matchedName = characters.firstOrNull { it.id == roleId }?.name
    return when {
        !matchedName.isNullOrBlank() -> matchedName
        roleId.isBlank() || roleId == DEFAULT_ROLE_ID -> UNSELECTED_ROLE_NAME
        else -> UNKNOWN_ROLE_NAME
    }
}

private fun formatSessionTimestamp(updatedAt: Long): String =
    if (updatedAt <= 0L) {
        ""
    } else {
        val now = Calendar.getInstance()
        val sessionTime = Calendar.getInstance().apply { timeInMillis = updatedAt }
        val isToday = now.get(Calendar.YEAR) == sessionTime.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == sessionTime.get(Calendar.DAY_OF_YEAR)
        val pattern = if (isToday) TODAY_TIME_PATTERN else DATE_PATTERN
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(updatedAt))
    }
