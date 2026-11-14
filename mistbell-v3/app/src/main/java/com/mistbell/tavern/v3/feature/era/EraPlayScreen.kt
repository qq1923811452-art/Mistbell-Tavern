@file:Suppress("FunctionNaming", "MagicNumber", "TooGenericExceptionCaught")

package com.mistbell.tavern.v3.feature.era

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistbell.tavern.v3.feature.era.engine.EraEngineManager
import com.mistbell.tavern.v3.feature.era.engine.TurnAwaiting
import com.mistbell.tavern.v3.feature.era.engine.TurnBgImage
import com.mistbell.tavern.v3.feature.era.engine.TurnButton
import com.mistbell.tavern.v3.feature.era.engine.TurnButtonItem
import com.mistbell.tavern.v3.feature.era.engine.TurnDisplayLine
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import java.io.File

private const val TAG_BUTTON = "era_btn"

/**
 * 立绘层开关。首帧渲染疑似导致主进程崩溃（引擎日志显示 start 成功后 1.6s 前端断开），
 * 先关掉隔离验证，确认后再定位具体是解码还是布局。
 */
private const val ENABLE_SPRITE_LAYERS = true

/** 立绘层上限：层数过多会反复解码大图，低内存设备直接闪退。 */
private const val MAX_LAYER_IMAGES = 4
private const val MAX_LAYER_FILE_BYTES = 64L * 1024 * 1024

/** 单张解码上限：超过就降采样，防止大 CG 直接 OOM。 */
private const val MAX_LAYER_PIXELS = 2_000_000

/** eraTW 按 1800×973 排版；图片尺寸从引擎像素换算 dp 的基准宽度。 */
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
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val session = remember { EraEngineManager.get(context) }
    // eraTW 的界面是按 1800×973 横屏排的；竖屏会把菜单行挤断（[ <<< ] 被拆行）。
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // 沉浸式：横屏后连导航栏/状态栏一起隐藏，下滑可临时唤出
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val state by session.state.collectAsStateWithLifecycle()

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
                .background(bgColor),
    ) {
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
    // 用户往上翻历史时停止自动跳底，回到底部再恢复跟随。
    var followTail by remember { mutableStateOf(true) }
    // 只在用户真正滚动时更新跟随状态。
    // 旧实现把 effect 挂在 lines.size 上：每次新行进来 effect 重启，首帧快照
    // 计算的是"旧位置是否在底部"，会把 followTail 误置为 false，于是再也不跟随。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    followTail = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
                }
            }
    }
    LaunchedEffect(revision, lastUid, followTail) {
        if (lines.isEmpty() || !followTail) return@LaunchedEffect
        listState.scrollToItem(lines.lastIndex)
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

/**
 * 立绘解码：先读边界再按需降采样。
 * eraTW 的 CG 单张可能几十兆像素——直接 decodeFile 会 OOM 闪退，
 * 文件大小阈值挡不住这种情况（压缩后可能只有几百 KB）。
 */
private fun decodeLayerBitmap(file: File): Bitmap? {
    // 文件体积与解码内存不成正比：10MB 可能只是 2048×2048 的背景，
    // 2MB 也可能解出 8000×8000。因此只留一个防损坏的上限，真正靠像素数降采样。
    if (!file.isFile || file.length() > MAX_LAYER_FILE_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while ((bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_LAYER_PIXELS) {
        sample *= 2
    }
    val opts =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            // 图层不需要高色深，RGB_565 直接省一半内存。
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

@Composable
private fun EraGameLayers(
    images: List<TurnBgImage>,
    gameDir: String?,
) {
    if (images.isEmpty() || gameDir == null) return
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 防御：只渲染有限的层，跳过超大文件，解码全程 try/catch——
        // 立绘素材动辄几 MB，一次性解码多张会在低内存机子上直接闪退。
        images.take(MAX_LAYER_IMAGES).forEach { layer ->
            val bitmap =
                remember(gameDir, layer.src) {
                    runCatching { decodeLayerBitmap(File(gameDir, layer.src)) }.getOrNull()
                }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .alpha(layer.opacity.coerceIn(0f, 1f)),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
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

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
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
