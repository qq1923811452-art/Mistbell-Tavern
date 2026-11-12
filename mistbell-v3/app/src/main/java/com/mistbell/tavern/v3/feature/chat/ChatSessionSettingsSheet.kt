@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "MagicNumber", "TooManyFunctions")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard
import com.mistbell.tavern.v3.ui.CharacterAvatar
import com.mistbell.tavern.v3.ui.tavern.TavernGlassCard
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernSectionLabel
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import com.mistbell.tavern.v3.ui.tavern.TavernSheetHeader

private const val SELECTED_ALPHA = 0.12f
private const val DANGER_BORDER_ALPHA = 0.35f
private const val SHEET_HEIGHT_FRACTION = 0.92f
private val CHARACTER_AVATAR_SIZE = 46.dp
private val CHARACTER_CHIP_WIDTH = 74.dp

/**
 * Session-scoped settings: the character and the transcript, not the global API.
 *
 * The sheet opens fully expanded and keeps its title pinned while the body scrolls. Previously the
 * whole sheet was one scrollable column capped at 560dp, so dragging the sheet scrolled its content
 * away instead of expanding it — the title vanished and the sheet never reached the top.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSessionSettingsSheet(
    characters: List<StoredCharacterCard>,
    selectedCharacterId: String,
    messageCount: Int,
    memorySummary: String?,
    onOpenMemory: () -> Unit,
    worldbooks: List<Pair<String, String>>,
    selectedWorldbookId: String,
    onSelectWorldbook: (String) -> Unit,
    onSelectCharacter: (String) -> Unit,
    onClearSession: () -> Unit,
    onImportSession: () -> Unit,
    onExportJson: () -> Unit,
    onExportJsonl: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TavernPalette.Ink,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION),
        ) {
            TavernSheetHeader(title = "会话设置", subtitle = "本会话 $messageCount 条消息")
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            ) {
                item {
                    Spacer(Modifier.height(4.dp))
                    TavernSectionLabel("会话记忆")
                    TavernGlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = memorySummary ?: "正在读取本会话记忆…",
                                color = TavernPalette.TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            )
                            TavernPill(
                                label = "查看与编辑记忆",
                                onClick = onOpenMemory,
                                filled = true,
                                glyph = TavernGlyphName.Prompt,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
                item {
                    TavernSectionLabel("当前角色", modifier = Modifier.padding(top = 18.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        characters.forEach { character ->
                            CharacterChoice(
                                name = character.card.name,
                                avatarFileName = character.card.avatarFileName,
                                selected = character.id == selectedCharacterId,
                                onClick = { onSelectCharacter(character.id) },
                            )
                        }
                    }
                }
                item {
                    TavernSectionLabel("当前世界书", modifier = Modifier.padding(top = 18.dp))
                    TavernGlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            WorldbookChoice(
                                name = "跟随全局",
                                selected = selectedWorldbookId.isBlank(),
                                onClick = { onSelectWorldbook("") },
                            )
                            worldbooks.forEach { (id, name) ->
                                SheetDivider()
                                WorldbookChoice(
                                    name = name,
                                    selected = id == selectedWorldbookId,
                                    onClick = { onSelectWorldbook(id) },
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TavernPill(label = "导入会话", onClick = onImportSession)
                        TavernPill(label = "导出 JSON", onClick = onExportJson)
                        TavernPill(label = "导出 JSONL", onClick = onExportJsonl)
                    }
                    TavernGlassCard(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        borderColor = TavernPalette.Danger.copy(alpha = DANGER_BORDER_ALPHA),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClick = onClearSession)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "清空本会话",
                                color = TavernPalette.Danger,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Selectable character chip: the character's own avatar above the name. */
@Suppress("FunctionNaming")
@Composable
private fun CharacterChoice(
    name: String,
    avatarFileName: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = TavernShape.CardSmall
    Column(
        modifier =
            Modifier
                .width(CHARACTER_CHIP_WIDTH)
                .clip(shape)
                .background(if (selected) TavernPalette.Purple.copy(alpha = SELECTED_ALPHA) else TavernPalette.Glass)
                .border(1.dp, if (selected) TavernPalette.Purple else TavernPalette.Border, shape)
                .clickable(role = Role.Button, onClickLabel = name, onClick = onClick)
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CharacterAvatar(
            name = name,
            avatarFileName = avatarFileName,
            size = CHARACTER_AVATAR_SIZE,
        )
        Text(
            text = name,
            color = if (selected) TavernPalette.TextPrimary else TavernPalette.TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** One worldbook option; the selected row keeps a purple dot instead of a trailing check mark. */
@Suppress("FunctionNaming")
@Composable
private fun WorldbookChoice(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = if (selected) TavernPalette.PurpleSoft else TavernPalette.TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(TavernPalette.Purple))
        }
    }
}

@Composable
private fun SheetDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(TavernPalette.Border))
}
