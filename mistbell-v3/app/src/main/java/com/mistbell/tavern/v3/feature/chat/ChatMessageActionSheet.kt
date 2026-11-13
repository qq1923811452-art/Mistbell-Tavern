@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.ui.tavern.TavernGlyph
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernShape

/** What the long-press sheet acts on: one message, its role and its visible text. */
internal data class ChatMessageActionTarget(
    val messageId: String,
    val isUser: Boolean,
    val content: String,
)

private val ACTION_SHAPE = RoundedCornerShape(16.dp)

/**
 * Long-press actions for one bubble.
 *
 * Regenerate used to be a permanent button under the newest answer; it is now one entry in this
 * sheet, together with copy / edit / delete, so the transcript is not decorated with controls.
 */
@Suppress("FunctionNaming", "LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMessageActionSheet(
    target: ChatMessageActionTarget,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TavernPalette.Ink,
        dragHandle = null,
        shape = TavernShape.Sheet,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(
                    text = if (target.isUser) "你的消息" else "角色消息",
                    color = TavernPalette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = target.content.replace('\n', ' ').trim(),
                    color = TavernPalette.TextTertiary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ActionRow(glyph = TavernGlyphName.Prompt, label = "复制", onClick = onCopy)
            ActionRow(glyph = TavernGlyphName.Menu, label = "编辑", onClick = onEdit)
            if (!target.isUser) {
                ActionRow(glyph = TavernGlyphName.Refresh, label = "重新生成", onClick = onRegenerate)
            }
            ActionRow(
                glyph = TavernGlyphName.Stop,
                label = "删除这条消息",
                onClick = onDelete,
                danger = true,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ActionRow(
    glyph: TavernGlyphName,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val tint = if (danger) TavernPalette.Danger else TavernPalette.TextSecondary
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(ACTION_SHAPE)
                .background(TavernPalette.Glass)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TavernGlyph(glyph, modifier = Modifier.size(18.dp), tint = tint, strokeWidth = 1.7.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = if (danger) TavernPalette.Danger else TavernPalette.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Inline text editor for one message; keeps the original role and position. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatMessageEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onConfirmAndRegenerate: ((String) -> Unit)? = null,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑这条消息", color = TavernPalette.TextPrimary) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 12,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onConfirm(draft) },
                    enabled = draft.isNotBlank(),
                ) { Text("保存") }
                if (onConfirmAndRegenerate != null) {
                    TextButton(
                        onClick = { onConfirmAndRegenerate.invoke(draft) },
                        enabled = draft.isNotBlank(),
                    ) { Text("保存并重生成") }
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = TavernPalette.Ink,
    )
}
