@file:Suppress("FunctionNaming", "MagicNumber", "TooGenericExceptionCaught")

package com.mistbell.tavern.v3.feature.era

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistbell.tavern.v3.BuildConfig
import com.mistbell.tavern.v3.feature.era.engine.EraEngineManager
import com.mistbell.tavern.v3.feature.era.engine.TurnAwaiting
import com.mistbell.tavern.v3.feature.era.engine.TurnButton
import com.mistbell.tavern.v3.feature.era.engine.TurnButtonItem
import com.mistbell.tavern.v3.feature.era.engine.TurnDisplayLine
import com.mistbell.tavern.v3.ui.tavern.TavernGlyphName
import com.mistbell.tavern.v3.ui.tavern.TavernIconButton
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape

private const val TAG_BUTTON = "era_btn"
private val ERA_GOLD_TEXT = Color(0xFFFFCE8A)
private val ERA_GOLD_BORDER = Color(0x4DFFCE8A)
private val ERA_GOLD_SOFT = Color(0x26FFCE8A)
private val ERA_LINE_HEIGHT = 21.sp
private val ERA_TEXT_SIZE = 14.sp
private val ERA_BUTTON_HEIGHT = 42.dp

@Suppress("LongMethod")
@Composable
internal fun EraPlayScreen(
    game: EraGameInstall,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val session = remember { EraEngineManager.get(context) }
    val state by session.state.collectAsStateWithLifecycle()
    var logLines by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(game.name, game.importedAtEpochMillis, game.zipPath) {
        session.ensureStarted(game)
    }
    DisposableEffect(session) {
        onDispose { session.detachUiSocket() }
    }

    val bgColor = remember(state.bgColor) { parseHexColor(state.bgColor) ?: TavernPalette.Night }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(bgColor)
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        EraPlayTopBar(
            name = game.name,
            status = state.statusText,
            detail = state.detail,
            onBack = onBack,
            onOpenSetup = onOpenSetup,
            onOpenLog = { logLines = readEngineLog(context) },
            onStopEngine = {
                session.shutdown()
                onOpenSetup()
            },
        )
        EraGameLog(
            lines = state.lines,
            revision = state.revision,
            generation = state.generation,
            onButtonClick = { btn -> session.submitButton(btn) },
            onBackgroundTap = { session.tapLogBackground() },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
        )
        state.inputHint?.let { hint ->
            Text(
                text = hint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = ERA_GOLD_TEXT,
                fontSize = 12.sp,
            )
        }
        state.error?.let { message ->
            EraErrorBanner(message = message, onRetry = { session.retry(game) })
        }
        logLines?.let { lines ->
            EraLogDialog(lines = lines, onDismiss = { logLines = null })
        }
        EraInputPanel(
            awaiting = state.awaiting,
            quickButtons = state.activeButtons,
            onButtonClick = { btn -> session.submitButton(btn) },
            onSend = { value -> session.submitInput(value) },
            onRestart = { session.retry(game) },
        )
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun EraPlayTopBar(
    name: String,
    status: String,
    detail: String?,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLog: () -> Unit,
    onStopEngine: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Text(
                text = (if (BuildConfig.ERA_MERGED) "单包 · " else "两包 · ") + status,
                color = TavernPalette.TextTertiary,
                fontSize = 11.sp,
                maxLines = 2,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = TavernPalette.TextTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = "日志",
            color = TavernPalette.TextSecondary,
            fontSize = 12.sp,
            modifier =
                Modifier
                    .clip(TavernShape.Pill)
                    .clickable(onClick = onOpenLog)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            text = "关闭",
            color = TavernPalette.Danger,
            fontSize = 12.sp,
            modifier =
                Modifier
                    .clip(TavernShape.Pill)
                    .clickable(onClick = onStopEngine)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            text = "设置",
            color = ERA_GOLD_TEXT,
            fontSize = 12.sp,
            modifier =
                Modifier
                    .clip(TavernShape.Pill)
                    .background(ERA_GOLD_SOFT)
                    .border(1.dp, ERA_GOLD_BORDER, TavernShape.Pill)
                    .clickable(onClick = onOpenSetup)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun EraGameLog(
    lines: List<TurnDisplayLine>,
    revision: Long,
    generation: Long,
    onButtonClick: (TurnButton) -> Unit,
    onBackgroundTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lastUid = lines.lastOrNull()?.uid
    LaunchedEffect(revision, lastUid) {
        if (lines.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible < 0 || lastVisible >= lines.size - 3
        if (nearBottom) listState.scrollToItem(lines.lastIndex)
    }

    LazyColumn(
        modifier =
            modifier.pointerInput(Unit) {
                detectTapGestures { onBackgroundTap() }
            },
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = lines, key = { it.uid }) { line ->
            EraRichLine(
                line = line,
                currentGeneration = generation,
                onButtonClick = onButtonClick,
                onBackgroundTap = onBackgroundTap,
            )
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun EraRichLine(
    line: TurnDisplayLine,
    currentGeneration: Long,
    onButtonClick: (TurnButton) -> Unit,
    onBackgroundTap: () -> Unit,
) {
    val buttonMap = remember(line.uid) { HashMap<String, TurnButton>() }
    val annotated: AnnotatedString =
        remember(line.uid, currentGeneration) {
            buttonMap.clear()
            buildAnnotatedString {
                line.entries.forEachIndexed { entryIdx, entry ->
                    val btn = entry.button
                    val isLiveButton = btn != null && btn.generation == currentGeneration
                    val tagId = if (isLiveButton) "b$entryIdx" else null
                    if (tagId != null && btn != null) {
                        buttonMap[tagId] = btn
                        pushStringAnnotation(tag = TAG_BUTTON, annotation = tagId)
                    }
                    for (seg in entry.segments) {
                        val segColor =
                            parseHexColor(seg.color)
                                ?: if (isLiveButton) ERA_GOLD_TEXT else TavernPalette.TextPrimary
                        val deco =
                            when {
                                seg.underline && seg.strikeout ->
                                    TextDecoration.combine(
                                        listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                                    )
                                seg.underline || isLiveButton -> TextDecoration.Underline
                                seg.strikeout -> TextDecoration.LineThrough
                                else -> null
                            }
                        pushStyle(
                            SpanStyle(
                                color = segColor,
                                fontWeight = if (seg.bold) FontWeight.Bold else null,
                                fontStyle = if (seg.italic) FontStyle.Italic else null,
                                textDecoration = deco,
                            ),
                        )
                        append(seg.text)
                        pop()
                    }
                    if (tagId != null) pop()
                }
            }
        }

    var layoutResult by remember(line.uid) { mutableStateOf<TextLayoutResult?>(null) }
    val align =
        when (line.align) {
            "center" -> TextAlign.Center
            "right" -> TextAlign.End
            else -> TextAlign.Start
        }

    Text(
        text = annotated,
        onTextLayout = { layoutResult = it },
        style =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = ERA_TEXT_SIZE,
                lineHeight = ERA_LINE_HEIGHT,
                textAlign = align,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(annotated, currentGeneration) {
                    detectTapGestures { pos ->
                        val layout = layoutResult
                        if (layout != null && buttonMap.isNotEmpty()) {
                            val offset = layout.getOffsetForPosition(pos)
                            val hit = annotated.getStringAnnotations(TAG_BUTTON, offset, offset).firstOrNull()
                            val targetBtn = hit?.let { buttonMap[it.item] }
                            if (targetBtn != null) {
                                onButtonClick(targetBtn)
                                return@detectTapGestures
                            }
                        }
                        onBackgroundTap()
                    }
                },
    )
}

@Composable
private fun EraErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(TavernShape.Card)
                    .background(TavernPalette.Danger.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            color = TavernPalette.Danger,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        TavernPill(
            label = "重试",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(ERA_BUTTON_HEIGHT),
            filled = true,
            contentDescription = "重新启动引擎",
        )
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun EraInputPanel(
    awaiting: TurnAwaiting?,
    quickButtons: List<TurnButtonItem>,
    onButtonClick: (TurnButton) -> Unit,
    onSend: (String) -> Unit,
    onRestart: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TavernShape.Card)
                .background(TavernPalette.Surface.copy(alpha = 0.94f))
                .border(1.dp, TavernPalette.Border, TavernShape.Card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (awaiting == null) {
            Text(text = "正在等待引擎首帧…", color = TavernPalette.TextTertiary, fontSize = 12.sp)
            return@Column
        }
        if (awaiting.isEnded) {
            if (awaiting.endedTerminal == "Quit") {
                TavernPill(
                    label = "重新开始",
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth().height(ERA_BUTTON_HEIGHT),
                    filled = true,
                    contentDescription = "重新开始游戏",
                )
            }
            return@Column
        }
        if (awaiting.inputType == "Void" || !awaiting.acceptsInput) {
            val waitText =
                buildString {
                    append("等待剧本推进")
                    if (awaiting.waitDisplayTime && (awaiting.waitDurationMs ?: 0L) > 0L) {
                        append("（${awaiting.waitDurationMs} ms）")
                    } else {
                        append("…")
                    }
                }
            Text(text = waitText, color = TavernPalette.TextTertiary, fontSize = 12.sp)
            return@Column
        }

        if (quickButtons.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickButtons.forEach { item ->
                    Text(
                        text = item.label,
                        color = TavernPalette.TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .clip(TavernShape.Pill)
                                .background(TavernPalette.GlassStrong)
                                .border(1.dp, ERA_GOLD_BORDER, TavernShape.Pill)
                                .clickable { onButtonClick(item.button) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        when {
            awaiting.needsTextValue ||
                (awaiting.inputType == "IntButton" || awaiting.inputType == "StrButton") && quickButtons.isEmpty() -> {
                EraValueInput(
                    numericOnly = awaiting.inputType == "IntValue",
                    onSend = onSend,
                )
            }
            awaiting.isTapToAdvance -> {
                TavernPill(
                    label = "继续",
                    onClick = { onSend("") },
                    modifier = Modifier.fillMaxWidth().height(ERA_BUTTON_HEIGHT),
                    filled = true,
                    contentDescription = "继续",
                )
            }
        }
    }
}

@Composable
private fun EraValueInput(
    numericOnly: Boolean,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
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
                Text(
                    text = if (numericOnly) "输入数字或点击上方选项" else "输入指令或文本",
                    color = TavernPalette.TextTertiary,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = TavernPalette.TextPrimary, fontSize = 13.sp),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (numericOnly) KeyboardType.Number else KeyboardType.Text,
                    ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        TavernPill(
            label = "发送",
            onClick = {
                onSend(text)
                text = ""
            },
            modifier = Modifier.height(ERA_BUTTON_HEIGHT),
            filled = true,
            contentDescription = "发送输入",
        )
    }
}

@Suppress("ReturnCount")
private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching {
        val clean = hex.trim().removePrefix("#")
        val argb =
            when (clean.length) {
                6 -> 0xFF000000L or clean.toLong(16)
                8 -> clean.toLong(16)
                else -> return null
            }
        Color(argb.toInt())
    }.getOrNull()
}
