package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard
import com.mistbell.tavern.v3.ui.CharacterAvatar
import com.mistbell.tavern.v3.ui.tavern.TavernDialog
import com.mistbell.tavern.v3.ui.tavern.TavernDialogActions
import com.mistbell.tavern.v3.ui.tavern.TavernDialogTitle
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernSectionLabel
import com.mistbell.tavern.v3.ui.tavern.TavernShape

private val DIALOG_AVATAR_SIZE = 38.dp
private const val SELECTED_ALPHA = 0.12f

/** Creates a conversation only after the user picks its character and worldbook. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun CreateSessionDialog(
    characters: List<StoredCharacterCard>,
    worldbooks: List<Pair<String, String>>,
    initialRoleId: String,
    onDismiss: () -> Unit,
    onConfirm: (roleId: String, worldbookId: String) -> Unit,
) {
    var roleId by remember {
        mutableStateOf(
            initialRoleId.takeIf { id -> characters.any { it.id == id } }
                ?: characters.firstOrNull()?.id.orEmpty(),
        )
    }
    var worldbookId by remember { mutableStateOf("") }

    TavernDialog(onDismissRequest = onDismiss) {
        TavernDialogTitle(title = "新建对话", subtitle = "选一位角色，再决定用哪本世界书")
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
        ) {
            TavernSectionLabel("选择角色")
            if (characters.isEmpty()) {
                Text(
                    text = "还没有角色卡，请先在设置里导入。",
                    color = TavernPalette.TextTertiary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            characters.forEach { card ->
                PickRow(
                    title = card.card.name,
                    avatarFileName = card.card.avatarFileName,
                    selected = card.id == roleId,
                    onClick = { roleId = card.id },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            TavernSectionLabel("选择世界书", modifier = Modifier.padding(top = 16.dp))
            PickRow(
                title = "跟随全局",
                avatarFileName = null,
                selected = worldbookId.isEmpty(),
                onClick = { worldbookId = "" },
                modifier = Modifier.padding(top = 6.dp),
                showAvatar = false,
            )
            worldbooks.forEach { (id, name) ->
                PickRow(
                    title = name,
                    avatarFileName = null,
                    selected = worldbookId == id,
                    onClick = { worldbookId = id },
                    modifier = Modifier.padding(top = 6.dp),
                    showAvatar = false,
                )
            }
        }
        TavernDialogActions(
            confirmLabel = "开始聊天",
            onConfirm = { onConfirm(roleId, worldbookId) },
            onDismiss = onDismiss,
            confirmEnabled = roleId.isNotBlank(),
        )
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun PickRow(
    title: String,
    avatarFileName: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showAvatar: Boolean = true,
) {
    val shape = TavernShape.CardSmall
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) TavernPalette.Purple.copy(alpha = SELECTED_ALPHA) else TavernPalette.Glass)
                .clickable(role = Role.RadioButton, onClickLabel = title, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showAvatar) {
            CharacterAvatar(
                name = title,
                avatarFileName = avatarFileName,
                size = DIALOG_AVATAR_SIZE,
            )
        }
        Text(
            text = title,
            color = if (selected) TavernPalette.TextPrimary else TavernPalette.TextSecondary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(TavernPalette.Purple))
        }
    }
}
