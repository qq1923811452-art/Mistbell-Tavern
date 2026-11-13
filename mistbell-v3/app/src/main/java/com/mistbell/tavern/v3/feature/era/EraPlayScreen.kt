@file:Suppress("FunctionNaming", "MagicNumber", "TooGenericExceptionCaught")

package com.mistbell.tavern.v3.feature.era

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistbell.tavern.v3.feature.era.engine.EraEngineSession
import com.mistbell.tavern.v3.feature.era.engine.EraLine
import com.mistbell.tavern.v3.feature.era.engine.EwpButton
import com.mistbell.tavern.v3.feature.era.engine.EwpInputRequest
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernIconButton
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape

private val ERA_GOLD_TEXT = Color(0xFFFFCE8A)
private val ERA_GOLD_BORDER = Color(0x4DFFCE8A)
private val ERA_GOLD_SOFT = Color(0x26FFCE8A)
private val ERA_LINE_HEIGHT = 21.sp
private val ERA_TEXT_SIZE = 14.sp
private val ERA_BUTTON_HEIGHT = 42.dp
private val ERA_PANEL_MAX_HEIGHT = 260.dp

/**
 * 正式游戏界面：文本与按钮全部由 Compose 原生绘制，引擎只负责世界状态与结算。
 *
 * 这样做的直接好处是 AI 叙事、划词复制、无障碍、输入法都能直接复用系统能力；
 * 以后要加立绘/背景，再在文本层下面挂引擎的像素或精灵层即可。
 */
@Composable
internal fun EraPlayScreen(
    game: EraGameInstall,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val session = remember(game.directory) { EraEngineSession(context) }
    val state by session.state.collectAsStateWithLifecycle()

    LaunchedEffect(game.directory) { session.start(game) }
    DisposableEffect(session) { onDispose { session.stop() } }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(TavernPalette.Night)
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        EraPlayTopBar(name = game.name, status = state.statusText, onBack = onBack, onOpenSetup = onOpenSetup)
        EraGameLog(
            lines = state.lines,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
        )
        state.error?.let { message -> EraErrorBanner(message) }
        EraInputPanel(
            input = state.input,
            onSend = { value, via -> session.submit(value, via) },
        )
    }
}

@Composable
private fun EraPlayTopBar(
    name: String,
    status: String,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TavernIconButton(glyph = TavernGlyphName.Back, onClick = onBack, contentDescription = "返回")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = TavernPalette.TextPrimary,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
            )
            Text(text = status, color = TavernPalette.TextTertiary, fontSize = 11.sp, maxLines = 1)
        }
        Text(
            text = "设置",
            color = ERA_GOLD_TEXT,
            fontSize = 12.sp,
            modifier =
                Modifier
                    .clip(TavernShape.Pill)
                    .background(ERA_GOLD_SOFT)
                    .border(1.dp, ERA_GOLD_BORDER, TavernShape.Pill)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(onClick = onOpenSetup),
        )
    }
}

@Composable
private fun EraGameLog(
    lines: List<EraLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line.text,
                color = if (line.text.startsWith("【系统】")) TavernPalette.TextTertiary else TavernPalette.TextPrimary,
                fontSize = ERA_TEXT_SIZE,
                lineHeight = ERA_LINE_HEIGHT,
            )
        }
    }
}

@Composable
private fun EraErrorBanner(message: String) {
    Text(
        text = message,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(TavernShape.Card)
                .background(TavernPalette.Danger.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        color = TavernPalette.Danger,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun EraInputPanel(
    input: EwpInputRequest?,
    onSend: (String, String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.Card)
                .background(TavernPalette.Surface.copy(alpha = 0.92f))
                .border(1.dp, TavernPalette.Border, TavernShape.Card)
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (input == null) {
            Text(text = "等待引擎…", color = TavernPalette.TextTertiary, fontSize = 12.sp)
            return@Column
        }
        if (input.buttons.isNotEmpty()) {
            EraButtonList(input = input, onSend = onSend)
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (input.needsValue) {
            EraValueInput(input = input, onSend = onSend)
        } else {
            TavernPill(
                label = "继续",
                onClick = { onSend("", "enter") },
                modifier = Modifier.fillMaxWidth().height(ERA_BUTTON_HEIGHT),
                filled = true,
                contentDescription = "继续",
            )
        }
    }
}

@Composable
private fun EraButtonList(
    input: EwpInputRequest,
    onSend: (String, String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = ERA_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        input.buttons.forEach { button -> EraButtonRow(button = button, onSend = onSend) }
    }
}

@Composable
private fun EraButtonRow(
    button: EwpButton,
    onSend: (String, String) -> Unit,
) {
    Text(
        text = button.text,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.Card)
                .background(TavernPalette.GlassStrong)
                .border(1.dp, TavernPalette.Border, TavernShape.Card)
                .clickable { onSend(button.value, "button") }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        color = TavernPalette.TextPrimary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}

@Composable
private fun EraValueInput(
    input: EwpInputRequest,
    onSend: (String, String) -> Unit,
) {
    var text by remember(input.requestId) { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(TavernShape.Card)
                    .background(TavernPalette.Glass)
                    .border(1.dp, TavernPalette.Border, TavernShape.Card)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (text.isEmpty()) {
                Text(text = "输入指令或文本", color = TavernPalette.TextTertiary, fontSize = 13.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = { value -> text = value },
                textStyle = TextStyle(color = TavernPalette.TextPrimary, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        TavernPill(
            label = "发送",
            onClick = {
                onSend(text, "text")
                text = ""
            },
            modifier = Modifier.height(ERA_BUTTON_HEIGHT),
            filled = true,
            contentDescription = "发送输入",
        )
    }
}
