@file:Suppress("MagicNumber", "FunctionNaming")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.ui.CharacterAvatar
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernIconButton
import com.mistbell.tavern.v3.ui.tavern.TavernPalette

private val HEADER_GRADIENT =
    Brush.verticalGradient(
        listOf(Color(0xD90A0611), Color(0x000A0611)),
    )
private val AVATAR_SIZE = 38.dp
private val HEADER_ICON_SIZE = 34.dp
private val HEADER_BOTTOM_PADDING = 4.dp

/**
 * Chat top bar.
 *
 * Text buttons for navigation are gone: the avatar returns to the session list, and every other
 * action is a stroke icon in one cluster on the right (character picker, prompt, settings). The
 * header fades into the artwork instead of sitting on its own bar, which also lifts the transcript
 * up by a whole row.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun ChatTopBar(
    characterName: String,
    avatarFileName: String?,
    showPrompt: Boolean,
    onOpenSessions: () -> Unit,
    onOpenCharacterPicker: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(HEADER_GRADIENT)
                .statusBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = HEADER_BOTTOM_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .clickable(
                        onClickLabel = "返回会话列表",
                        role = Role.Button,
                        onClick = onOpenSessions,
                    ),
        ) {
            CharacterAvatar(
                name = characterName,
                avatarFileName = avatarFileName,
                size = AVATAR_SIZE,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
        ) {
            Text(
                text = characterName,
                color = TavernPalette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "左右滑动切换角色",
                color = TavernPalette.TextTertiary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TavernIconButton(
                glyph = TavernGlyphName.Group,
                onClick = onOpenCharacterPicker,
                contentDescription = "换角色",
                size = HEADER_ICON_SIZE,
            )
            if (showPrompt) {
                TavernIconButton(
                    glyph = TavernGlyphName.Prompt,
                    onClick = onOpenPrompt,
                    contentDescription = "查看本次提示词",
                    size = HEADER_ICON_SIZE,
                )
            }
            TavernIconButton(
                glyph = TavernGlyphName.Gear,
                onClick = onOpenSettings,
                contentDescription = "会话设置",
                size = HEADER_ICON_SIZE,
            )
        }
    }
}
