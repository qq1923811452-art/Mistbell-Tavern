@file:Suppress("MagicNumber", "FunctionNaming")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard
import com.mistbell.tavern.v3.ui.CharacterPortrait
import com.mistbell.tavern.v3.ui.tavern.TavernGlassCard
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import com.mistbell.tavern.v3.ui.tavern.TavernSheetHeader
import kotlinx.coroutines.launch

private val PAGE_HORIZONTAL_PADDING = 56.dp
private val PAGE_SPACING = 18.dp
private val PORTRAIT_MAX_HEIGHT = 430.dp

/**
 * Character picker as a swipeable portrait deck.
 *
 * A dropdown of names made every character look identical; the only thing that distinguishes one
 * persona from another is the picture. Pages carry the standing portrait, the card name and the
 * tags, and the currently bound character opens on its own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterPickerSheet(
    characters: List<StoredCharacterCard>,
    selectedCharacterId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val initialPage =
        remember(characters, selectedCharacterId) {
            characters.indexOfFirst { it.id == selectedCharacterId }.takeIf { it >= 0 } ?: 0
        }
    val pagerState = rememberPagerState(initialPage = initialPage) { characters.size.coerceAtLeast(1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TavernPalette.DeepGrape,
        contentColor = TavernPalette.TextPrimary,
        scrimColor = TavernPalette.Night.copy(alpha = 0.72f),
        dragHandle = null,
        shape = TavernShape.Sheet,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            TavernSheetHeader(
                title = "选择角色",
                subtitle = if (characters.isEmpty()) "角色库还是空的" else "左右滑动切换立绘 · 共 ${characters.size} 位",
            )
            if (characters.isEmpty()) {
                EmptyCharacterHint()
            } else {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = PAGE_HORIZONTAL_PADDING),
                    pageSpacing = PAGE_SPACING,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    val item = characters[page]
                    CharacterPage(
                        item = item,
                        selected = item.id == selectedCharacterId,
                        onSelect = {
                            onSelect(item.id)
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        },
                    )
                }
                PagerDots(count = characters.size, current = pagerState.currentPage)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CharacterPage(
    item: StoredCharacterCard,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CharacterPortrait(
            name = item.card.name,
            portraitFileName = item.card.portraitFileName,
            modifier = Modifier.fillMaxWidth().heightIn(max = PORTRAIT_MAX_HEIGHT),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = item.card.name,
            color = TavernPalette.TextPrimary,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val subtitle = item.card.tags.take(3).joinToString(" · ").ifBlank { "未填写标签" }
        Text(
            text = subtitle,
            color = TavernPalette.TextTertiary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TavernPill(
            label = if (selected) "当前角色" else "使用此角色",
            onClick = onSelect,
            glyph = if (selected) TavernGlyphName.Plus else null,
            filled = !selected,
            contentDescription = "选择 ${item.card.name}",
        )
    }
}

@Composable
private fun PagerDots(
    count: Int,
    current: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) TavernPalette.Purple else TavernPalette.BorderStrong,
                        )
                        .then(Modifier.fillMaxWidth(if (active) 0.035f else 0.012f)),
            )
        }
    }
}

@Composable
private fun EmptyCharacterHint() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        TavernGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "还没有角色卡",
                    color = TavernPalette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "先到「设置 → 角色卡管理」新建或导入角色卡，再回到这里左右滑动选择。",
                    color = TavernPalette.TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
