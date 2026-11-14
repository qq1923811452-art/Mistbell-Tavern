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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistbell.tavern.v3.feature.era.engine.EraEngineManager
import com.mistbell.tavern.v3.feature.era.engine.TurnBgImage
import com.mistbell.tavern.v3.feature.era.engine.TurnButton
import com.mistbell.tavern.v3.feature.era.engine.TurnDisplayLine
import com.mistbell.tavern.v3.feature.era.ui.EraActionDock
import com.mistbell.tavern.v3.feature.era.ui.EraStatusBar
import com.mistbell.tavern.v3.feature.era.ui.parseEraHud
import com.mistbell.tavern.v3.ui.tavern.TavernPalette
import com.mistbell.tavern.v3.ui.tavern.TavernPill
import com.mistbell.tavern.v3.ui.tavern.TavernShape
import java.io.File

/**
 * 立绘层开关。首帧渲染疑似导致主进程崩溃（引擎日志显示 start 成功后 1.6s 前端断开），
 * 先关掉隔离验证，确认后再定位具体是解码还是布局。
 */
private const val ENABLE_SPRITE_LAYERS = true

/**
 * 文本里的方括号按钮是否隐藏。
 *
 * era 的按钮本来就是「文字里套一层 []」，现在统一收到右侧动作栏里，
 * 正文再画一遍只会让屏幕更乱，所以默认不画。
 * 若遇到某些剧本把关键选项混在长句中间，把这个开关关掉即可恢复。
 */
private const val HIDE_INLINE_BUTTONS = true

/** 立绘层上限：层数过多会反复解码大图，低内存设备直接闪退。 */
private const val MAX_LAYER_IMAGES = 4
private const val MAX_LAYER_FILE_BYTES = 64L * 1024 * 1024

/** 单张解码上限：超过就降采样，防止大 CG 直接 OOM。 */
private const val MAX_LAYER_PIXELS = 2_000_000

private const val TAG_BUTTON = "era_btn"

private val ERA_GOLD_TEXT = Color(0xFFFFCE8A)
private val ERA_LINE_HEIGHT = 20.sp
private val ERA_TEXT_SIZE = 13.5.sp

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
        // 必须让内容延伸到系统栏区域，否则部分 ROM（MIUI）仍会保留状态栏占位。
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
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
    val hud = remember(state.revision, state.lines) { parseEraHud(state.lines) }
    var logOpen by remember { mutableStateOf(false) }
    // activeButtons 每次访问都要扫一遍全屏文本，这里只算一次。
    val dockButtons = remember(state.lines, state.generation, state.awaiting) { state.activeButtons }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(bgColor),
    ) {
        EraStatusBar(
            hud = hud,
            statusText = state.statusText,
            connected = state.connected,
            onOpenLog = { logOpen = true },
            onOpenMenu = onBack,
        )
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (ENABLE_SPRITE_LAYERS) {
                        EraGameLayers(images = state.bgImages, gameDir = state.gameDir)
                    }
                    EraGameLog(
                        lines = state.lines,
                        revision = state.revision,
                        generation = state.generation,
                        inlineFallback = dockButtons.isEmpty(),
                        onButtonClick = { btn -> session.submitButton(btn) },
                        onBackgroundTap = { session.tapLogBackground() },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
                    )
                }
                state.inputHint?.let { hint ->
                    Text(
                        text = hint,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 4.dp),
                        color = ERA_GOLD_TEXT,
                        fontSize = 12.sp,
                    )
                }
                state.error?.let { message ->
                    EraErrorBanner(message = message, onRetry = { session.retry(game) })
                }
            }
            EraActionDock(
                items = dockButtons,
                awaiting = state.awaiting,
                onButton = { btn -> session.submitButton(btn) },
                onSend = { value -> session.submitInput(value) },
                onRestart = { session.retry(game) },
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }

    if (logOpen) {
        EraLogSheet(lines = state.lines, onDismiss = { logOpen = false })
    }
}

@Suppress("LongParameterList")
@Composable
private fun EraGameLog(
    lines: List<TurnDisplayLine>,
    revision: Long,
    generation: Long,
    inlineFallback: Boolean,
    onButtonClick: (TurnButton) -> Unit,
    onBackgroundTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lastUid = lines.lastOrNull()?.uid
    // 静屏：永远跟随最新一行，不保留「往上翻历史」的滚动手感（历史走日志抽屉）。
    LaunchedEffect(revision, lastUid) {
        if (lines.isEmpty()) return@LaunchedEffect
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
                generation = generation,
                interactive = inlineFallback,
                onButtonClick = onButtonClick,
                onBackgroundTap = onBackgroundTap,
            )
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
private fun EraRichLine(
    line: TurnDisplayLine,
    generation: Long,
    interactive: Boolean,
    onButtonClick: (TurnButton) -> Unit,
    onBackgroundTap: () -> Unit,
) {
    val buttonMap = remember(line.uid) { HashMap<String, TurnButton>() }
    val annotated: AnnotatedString =
        remember(line.uid, generation, interactive) {
            buttonMap.clear()
            buildAnnotatedString {
                line.entries.forEachIndexed { entryIdx, entry ->
                    val btn = entry.button
                    // 只有「动作栏空着」时才退回正文按钮，且不再按 generation 过滤：
                    // 某些剧本把菜单画一次留好几回合，过滤会把它们全弄失效。
                    val live = interactive && btn != null
                    val tagId = if (live) "b$entryIdx" else null
                    if (tagId != null && btn != null) {
                        buttonMap[tagId] = btn
                        pushStringAnnotation(tag = TAG_BUTTON, annotation = tagId)
                    }
                    if (HIDE_INLINE_BUTTONS && !interactive && btn != null) {
                        if (tagId != null) pop()
                        return@forEachIndexed
                    }
                    for (seg in entry.segments) {
                        val segColor =
                            parseHexColor(seg.color)
                                ?: if (live) ERA_GOLD_TEXT else TavernPalette.TextPrimary
                        val deco =
                            when {
                                seg.underline && seg.strikeout ->
                                    TextDecoration.combine(
                                        listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                                    )
                                seg.underline || live -> TextDecoration.Underline
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
    val textModifier =
        if (interactive) {
            Modifier
                .fillMaxWidth()
                .pointerInput(annotated, generation) {
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
                }
        } else {
            Modifier.fillMaxWidth()
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
        modifier = textModifier,
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
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(TavernShape.CardSmall)
                    .background(TavernPalette.Danger.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            color = TavernPalette.Danger,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        TavernPill(
            label = "重试",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            filled = true,
            contentDescription = "重新启动引擎",
        )
    }
}

/** 完整历史日志：抽屉式，只在需要时打开，不占主界面。 */
@Composable
private fun EraLogSheet(
    lines: List<TurnDisplayLine>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0x8C000000))) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .fillMaxWidth(0.86f)
                        .clip(TavernShape.CardSmall)
                        .background(TavernPalette.Surface)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "日志",
                        color = TavernPalette.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 3.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "关闭",
                        color = TavernPalette.TextTertiary,
                        fontSize = 12.sp,
                        modifier =
                            Modifier
                                .clip(TavernShape.Pill)
                                .background(TavernPalette.Glass)
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp)) {
                    items(items = lines, key = { it.uid }) { line ->
                        EraRichLine(
                            line = line,
                            generation = 0L,
                            interactive = false,
                            onButtonClick = {},
                            onBackgroundTap = onDismiss,
                        )
                    }
                }
            }
        }
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
