@file:Suppress("MagicNumber", "FunctionNaming")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/**
 * Chat top bar.
 *
 * The old bar carried two text buttons ("返回首页" and "会话") that pointed at the same place, plus a
 * light Material surface that floated above dark artwork. Now the character avatar *is* the way
 * back to the session list, the name opens the portrait carousel, and the page has no chrome of its
 * own — the header fades into the artwork instead of sitting on a bar.
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(HEADER_GRADIENT)
                .statusBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(AVATAR_SIZE),
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
                if (showPrompt) {
                    TavernIconButton(
                        glyph = TavernGlyphName.Prompt,
                        onClick = onOpenPrompt,
                        contentDescription = "查看本次提示词",
                        size = 36.dp,
                    )
                }
                TavernIconButton(
                    glyph = TavernGlyphName.Gear,
                    onClick = onOpenSettings,
                    contentDescription = "会话设置",
                    size = 36.dp,
                )
            }
        }
        SessionEntryRow(
            avatarFileName = avatarFileName,
            onOpenSessions = onOpenSessions,
            onOpenCharacterPicker = onOpenCharacterPicker,
        )
    }
}

/**
 * One tap target per intent. Kept as a separate row so the avatar stays a plain picture: tapping a
 * face to leave the conversation reads as an accidental navigation.
 */
@Composable
private fun SessionEntryRow(
    avatarFileName: String?,
    onOpenSessions: () -> Unit,
    onOpenCharacterPicker: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TavernPillInline(
            label = "会话",
            glyph = TavernGlyphName.Back,
            onClick = onOpenSessions,
        )
        TavernPillInline(
            label = "换角色",
            glyph = TavernGlyphName.Group,
            onClick = onOpenCharacterPicker,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (avatarFileName == null) "未设置形象" else "",
            color = TavernPalette.TextTertiary,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun TavernPillInline(
    label: String,
    glyph: TavernGlyphName,
    onClick: () -> Unit,
) {
    com.mistbell.tavern.v3.ui.tavern.TavernPill(
        label = label,
        onClick = onClick,
        glyph = glyph,
    )
}
