package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.feature.sessions.ChatSession
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
) {
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    val nowCalendar = Calendar.getInstance()
    val sessionCalendar = Calendar.getInstance().apply { timeInMillis = session.updatedAt }
    val currentYear = nowCalendar.get(Calendar.YEAR)
    val sessionYear = sessionCalendar.get(Calendar.YEAR)
    val isToday = currentYear == sessionYear &&
        nowCalendar.get(Calendar.DAY_OF_YEAR) == sessionCalendar.get(Calendar.DAY_OF_YEAR)
    val dateFormat = when {
        session.updatedAt <= 0L -> ""
        isToday -> "HH:mm"
        currentYear == sessionYear -> "MM/dd"
        else -> "yyyy/MM/dd"
    }
    val time = remember(session.updatedAt, dateFormat) {
        if (dateFormat.isEmpty()) {
            ""
        } else {
            SimpleDateFormat(dateFormat, Locale.getDefault()).format(Date(session.updatedAt))
        }
    }
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.secondaryContainer else colors.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 0.dp,
            color = if (selected) colors.primary else colors.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(colors.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (session.roleId.firstOrNull() ?: session.title.firstOrNull() ?: '对').toString(),
                    color = colors.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (session.pinned) {
                        Text("置顶", color = colors.primary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(time, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    text = "${session.roleId} · ${session.preview}",
                    modifier = Modifier.padding(top = 4.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                Text(
                    text = "⋮",
                    modifier = Modifier
                        .combinedClickable(onClick = { menuExpanded = true }, onLongClick = { menuExpanded = true })
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (session.pinned) "取消置顶" else "置顶") },
                        onClick = { menuExpanded = false; onTogglePin(!session.pinned) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}
