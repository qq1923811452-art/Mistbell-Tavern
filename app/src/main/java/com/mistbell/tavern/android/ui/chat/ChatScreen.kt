@file:Suppress("TooManyFunctions", "FunctionNaming")

package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistbell.tavern.android.data.prompt.PromptBuilder
import com.mistbell.tavern.android.data.prompt.PromptBuilder.PromptTrace
import com.mistbell.tavern.android.data.theme.resolved
import com.mistbell.tavern.android.ui.common.rememberBitmap
import com.mistbell.tavern.android.ui.common.rememberFileBitmap
import com.mistbell.tavern.android.ui.theme.*
import com.mistbell.tavern.android.ui.utils.clearFocusOnTap

// —— 常量：集中定义，避免散落的魔法数字 ——
// 判定“贴底”的余量：最后可见项 index >= totalItemsCount - 2 即视为贴底
private const val BOTTOM_ANCHOR_SLACK = 2

// 判定“接近顶部需加载更旧消息”的阈值：第一个可见项 index <= 1
private const val LOAD_OLDER_THRESHOLD_INDEX = 1

// 流式占位 item 的固定 key：保证 streamingText 增量只重组该 item
private const val STREAMING_ITEM_KEY = "streaming"
private const val BOTTOM_ANCHOR_ITEM_KEY = "bottom-anchor"

// 列表顶/底渐隐遮罩高度（覆盖层绘制用）
private val MessageListTopFadeHeight = 72.dp
private val MessageListBottomFadeHeight = 88.dp

// 角色背景大图解码上限（px）：整屏显示 1280 已足够清晰，无需原始分辨率
private const val BACKGROUND_BITMAP_MAX_DIM_PX = 1280
private val DefaultChatInputSurfaceHeight = 64.dp
private val MessageToInputGap = 10.dp

// 背景图只作为氛围层，不能和消息正文争夺对比度。
private const val CHARACTER_BACKGROUND_ALPHA = 0.5f

/**
 * 修复2：上滚 prepend 旧消息时的阅读位置锚点。
 * LazyColumn 默认按索引保留位置，头部插入 N 条后正在读的消息会被整体下推；
 * 触发加载前记录 (firstVisibleItemIndex, firstVisibleItemScrollOffset, 旧 messages.size)，
 * 列表增长后按 delta = 新 size - 旧 size 恢复到同一内容位置。
 */
private data class PrependAnchor(
    val firstVisibleIndex: Int,
    val firstVisibleOffset: Int,
    val oldSize: Int,
)

@Composable
private fun buildTextAvatar(character: com.mistbell.tavern.android.data.api.model.Character) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(top = 120.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .size(280.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    character.color?.let {
                                        try {
                                            Color(android.graphics.Color.parseColor(it))
                                                .copy(alpha = 0.08f)
                                        } catch (_: Exception) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                        }
                                    } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.0f),
                                ),
                            radius = 400f,
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = character.name.take(1),
                fontSize = 180.sp,
                fontWeight = FontWeight.Bold,
                color =
                    character.color?.let {
                        try {
                            Color(android.graphics.Color.parseColor(it))
                                .copy(alpha = 0.05f)
                        } catch (_: Exception) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                        }
                    } ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            )
        }
    }
}

/**
 * Chat message edge fades and the solid base behind the bottom input surface.
 * The measured input surface is the single coordinate reference for the bottom fade,
 * keeping its endpoint attached to the actual input surface during IME/inset changes.
 */
@Composable
@Suppress("FunctionNaming")
private fun ChatFadeOverlay(surfaceHeight: Dp) {
    val fadeColor = MaterialTheme.colorScheme.background
    Canvas(modifier = Modifier.fillMaxSize()) {
        val surfaceTop = (size.height - surfaceHeight.toPx()).coerceAtLeast(0f)
        drawRect(
            color = fadeColor,
            topLeft = androidx.compose.ui.geometry.Offset(0f, surfaceTop),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - surfaceTop),
        )
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(fadeColor, Color.Transparent),
                    startY = 0f,
                    endY = MessageListTopFadeHeight.toPx(),
                ),
        )
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, fadeColor),
                    startY = (surfaceTop - MessageListBottomFadeHeight.toPx()).coerceAtLeast(0f),
                    endY = surfaceTop,
                ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    // 注意：streamingText 不在顶层收集——订阅已下放到 StreamingSlot，
    // 流式增量只重组对应的列表 item，不再触发整屏重组
    val hasMoreOlder by viewModel.hasMoreOlder.collectAsStateWithLifecycle()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsStateWithLifecycle()
    val sessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val currentCharacter by viewModel.currentCharacter.collectAsStateWithLifecycle()
    val participantCharacters by viewModel.participantCharacters.collectAsStateWithLifecycle()
    // 群聊模式：按消息归属渲染说话方 + "让TA继续"入口均以此开关
    val groupMode by viewModel.groupMode.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    // 项目②：会话级 API 实测用量聚合（顶栏命中率）与最近一条实测（调试面板对照）
    val usageSummary by viewModel.usageSummary.collectAsStateWithLifecycle()
    val latestAssistantUsage by viewModel.latestAssistantUsage.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val displayCharacters =
        participantCharacters.ifEmpty {
            currentCharacter?.let { listOf(it) } ?: emptyList()
        }
    val primaryDisplayCharacter = displayCharacters.firstOrNull() ?: currentCharacter
    // 群聊按消息渲染说话方：参与者 id→Character 映射，列表变化时重算一次（associateBy O(n)）
    val participantById = remember(participantCharacters) { participantCharacters.associateBy { it.id } }
    // 参与者 id→气泡颜色映射：颜色字符串解析只在参与者列表变化时执行一次，
    // 不进入每条消息的组合路径；解析失败的角色不出现在映射中，回落主角色颜色
    val participantColors =
        remember(participantCharacters) {
            participantCharacters.mapNotNull { c ->
                c.color.takeIf { it.isNotBlank() }?.let { colorStr ->
                    try {
                        c.id to Color(android.graphics.Color.parseColor(colorStr))
                    } catch (_: Exception) {
                        null
                    }
                }
            }.toMap()
        }
    val chatTitle =
        displayCharacters.joinToString("、") { it.name }.ifBlank {
            currentCharacter?.name ?: "AI"
        }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var inputSurfaceHeight by remember { mutableStateOf(0.dp) }
    val messageListTopPadding = if (!isOnline) 56.dp else 24.dp
    // 底部栏高度只从整个 Surface 测量，消息列表和渐隐遮罩共享同一来源。
    // 首帧尚未测量时使用固定兜底，避免根据输入内容和 inset 分别估算造成跳动。
    val effectiveInputSurfaceHeight =
        inputSurfaceHeight.takeIf { it > 0.dp } ?: DefaultChatInputSurfaceHeight
    // 输入栏本身由父容器预留；消息与输入栏之间的间距由尾部锚点 item 提供。
    val messageListBottomInset = effectiveInputSurfaceHeight
    // 普通消息从 0 开始，流式项紧接在消息之后，尾部锚点负责把最后一条长消息
    // 的底部贴到输入栏上方。空列表且未生成时不会触发滚动；保底 0 防止竞态。
    val bottomAnchorIndex = (messages.size + if (isTyping) 1 else 0).coerceAtLeast(0)
    // 最后一条消息 id 只需算一次：原先在每个 item 内读 messages.lastOrNull()，
    // 相当于每个 item 都订阅整个列表状态
    val lastMessageId = messages.lastOrNull()?.id
    // 开场白切换（聊天界面）：仅当会话只剩开场白这一条 AI 消息（没人接话）且有多个
    // 选项时提供入口——已有对话历史后开场白属于既成上下文，不允许改写
    val greetingOptions by viewModel.greetingOptions.collectAsStateWithLifecycle()
    val greetingMessage = messages.singleOrNull()?.takeIf { it.role == "assistant" }
    val canSwapGreeting = greetingMessage != null && greetingOptions.size > 1
    var showGreetingSheet by remember { mutableStateOf(false) }
    // 提示词预览（顶栏文档图标 → 底部抽屉）：列出本次请求真实发出的全部 message
    val promptTrace by viewModel.promptTrace.collectAsStateWithLifecycle()
    val promptRequestParams by viewModel.promptRequestParams.collectAsStateWithLifecycle()
    val isLoadingPromptTrace by viewModel.isLoadingPromptTrace.collectAsStateWithLifecycle()
    val promptTraceError by viewModel.promptTraceError.collectAsStateWithLifecycle()
    var showPromptSheet by remember { mutableStateOf(false) }
    // “贴底”判定：derivedStateOf 只在最后可见项 index 跨过阈值时才触发重组
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            // 无可见项（尚未完成布局）视为贴底，避免首帧被误判为“正在阅读历史”
            visibleItems.isEmpty() ||
                visibleItems.last().index >= layoutInfo.totalItemsCount - BOTTOM_ANCHOR_SLACK
        }
    }
    // 区分首次加载与用户阅读历史：会话切换（sessionId 变化）时重置
    var jumpedToBottom by remember(sessionId) { mutableStateOf(false) }
    // 修复2：上滚 prepend 旧消息的阅读位置锚点（解释见 PrependAnchor 注释）。
    // 只在"即将触发加载"的瞬间被赋值，列表增长后消费并清空
    var pendingAnchor by remember { mutableStateOf<PrependAnchor?>(null) }
    // 切换会话时丢弃旧会话的分页锚点，避免异步加载结果回到新会话后误用旧位置。
    LaunchedEffect(sessionId) {
        pendingAnchor = null
    }
    // 修复2：列表增长后按锚点恢复阅读位置。
    // key 必须是 messages.size：prepend 后 size 变化触发本 effect，用 scrollToItem
    // （瞬时、无动画）恢复到 原firstVisibleIndex + delta，像素偏移也一并还原，
    // 视觉上正在读的消息纹丝不动
    LaunchedEffect(messages.size) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        val delta = messages.size - anchor.oldSize
        if (delta > 0) {
            listState.scrollToItem(anchor.firstVisibleIndex + delta, anchor.firstVisibleOffset)
        }
        // delta<=0（加载失败/空结果）同样清空，避免过期锚点被后续加载误用
        pendingAnchor = null
    }
    // 修复1：key 必须含 sessionId —— loadSession 不清空 _messages，两个长会话都是
    // 200 条时 messages.size 不变，仅靠 (size, isTyping) 做 key 时 effect 不重启；
    // jumpedToBottom 虽被 remember(sessionId) 复位，却没有任何 effect 再执行，
    // 导致切会话后列表停留在旧滚动位置。加入 sessionId 后每次切会话必然重新执行，
    // 完成一次"跳到底部"的开场定位
    LaunchedEffect(sessionId, messages.size, isTyping) {
        if (!jumpedToBottom) {
            if (messages.isNotEmpty() || isTyping) {
                // 首次加载（消息从空到非空）：瞬时跳到底部，不做动画，
                // 避免开场动画扫过整段历史消息
                listState.scrollToItem(bottomAnchorIndex)
                jumpedToBottom = true
            }
        } else if (atBottom) {
            // 只有用户仍贴底时才跟随新消息/typing 变化；
            // 用户上翻阅读历史时绝不拉回底部
            listState.scrollToItem(bottomAnchorIndex)
        }
    }
    LaunchedEffect(imeBottom, inputSurfaceHeight) {
        if (imeBottom > 0 && (messages.isNotEmpty() || isTyping)) {
            if (!jumpedToBottom || atBottom) {
                // IME 弹出同样非动画跟随，且阅读历史时不拉回
                listState.scrollToItem(bottomAnchorIndex)
            }
        }
    }
    // 顶部加载旧消息：第一个可见项接近列表头时触发；
    // 防并发/防重入/到头返回由 ViewModel 内部实现
    val shouldLoadOlder by remember {
        derivedStateOf {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            firstVisible != null && firstVisible.index <= LOAD_OLDER_THRESHOLD_INDEX
        }
    }
    // 修复3：key 必须含 isLoadingOlder —— 加载结束后 isLoadingOlder true→false 变化
    // 会重启本 effect；否则若 shouldLoadOlder 全程保持 true（修复2索引保留后首项仍在顶部
    // 阈值内），effect 不重启，第 2 页之后分页永远停摆。锚定恢复后 firstVisibleItemIndex
    // 变大 → shouldLoadOlder 变 false，用户再滚回顶部才继续加载，链条自然收敛
    LaunchedEffect(shouldLoadOlder, hasMoreOlder, isLoadingOlder) {
        if (shouldLoadOlder && hasMoreOlder && !isLoadingOlder) {
            // 修复2：触发加载前记录阅读位置锚点（当前首可见项 index/像素偏移 + 旧 size）
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { first ->
                pendingAnchor =
                    PrependAnchor(
                        firstVisibleIndex = first.index,
                        firstVisibleOffset = first.offset,
                        oldSize = messages.size,
                    )
            }
            viewModel.loadOlderMessages()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }
    // 已读标记不再由 UI 触发：改由 ChatViewModel 在消息流首次发射后自动执行
    // （仅未读数 > 0 时写库），避免进页面必然多一次写库
    // 主题包状态：tokens / 背景图 / 深色模式（三态判定与 Theme.kt 保持一致，避免 dark 覆盖错配）
    val characterTokens by viewModel.characterTokens.collectAsStateWithLifecycle()
    val characterBackgroundFile by viewModel.characterBackgroundFile.collectAsStateWithLifecycle()
    val darkModeSetting by viewModel.darkModeSetting.collectAsStateWithLifecycle()
    val baseScheme = MaterialTheme.colorScheme
    val isDark =
        when (darkModeSetting) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    // 记忆化：tokens/深色态不变时 resolved() 只计算一次
    val eff = remember(characterTokens, isDark) { characterTokens?.resolved(isDark) }
    // 主角色气泡颜色：解析提入 remember（角色不变则只解析一次）；
    // 主题回退色在组合期读取，避免 remember 块内读 snapshot state 被固化
    val primaryCharacterColor =
        remember(primaryDisplayCharacter?.color) {
            primaryDisplayCharacter?.color?.let { colorString ->
                try {
                    Color(android.graphics.Color.parseColor(colorString))
                } catch (_: Exception) {
                    null
                }
            }
        } ?: MaterialTheme.colorScheme.primary
    // 背景图异步采样解码：复用公共工具 + LRU 缓存，不再在组合线程同步解码大图
    val bgBitmap = rememberFileBitmap(characterBackgroundFile?.absolutePath, BACKGROUND_BITMAP_MAX_DIM_PX)
    val avatarData = primaryDisplayCharacter?.avatarData.orEmpty()
    val avatarBitmap = rememberBitmap(avatarData.takeIf { it.isNotBlank() }, BACKGROUND_BITMAP_MAX_DIM_PX)
    val hasChatBackground = bgBitmap != null || avatarBitmap != null
    // 原有聊天内容整体作为 lambda，按需包裹主题覆盖与背景图
    val chatContent: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background with character avatar image
            // 有主题包背景图时此层必须透明，否则会把背景图盖住
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // 有主题背景图时让中间消息区透出背景；顶部和底部仍由各自容器保持不透明。
                        .background(
                            if (hasChatBackground) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.background
                            },
                        ),
            ) {
                // Character avatar as faded background
                primaryDisplayCharacter?.let { character ->
                    if (avatarData.isNotBlank()) {
                        // 背景大图异步采样解码（复用公共缓存工具），失败回落文字头像
                        if (avatarBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = avatarBitmap,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .alpha(CHARACTER_BACKGROUND_ALPHA),
                                // 降低背景存在感，保证消息正文仍是视觉焦点
                                // 裁剪填充整个屏幕
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            // Fallback to text avatar if bitmap parsing fails
                            buildTextAvatar(character)
                        }
                    } else {
                        // Fallback to text avatar if no image
                        buildTextAvatar(character)
                    }
                }
            }
            // Main content
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    ModernChatTopBar(
                        // 待实现：根据滚动状态动态更新 isScrolled
                        chatTitle = chatTitle,
                        displayCharacters = displayCharacters,
                        isScrolled = false,
                        onBackClick = onMenuClick,
                        onPromptClick = {
                            viewModel.loadPromptTrace()
                            showPromptSheet = true
                        },
                        onSettingsClick = onSettingsClick,
                        // 项目②：会话级 API 实测用量（0 轮 = 网关未上报 → 顶栏不显示用量）
                        usageRounds = usageSummary.messageCount,
                        usageTotalTokens = usageSummary.totalTokens,
                        usageCacheHitRate = usageSummary.cacheHitRate,
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor =
                    if (hasChatBackground) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.background
                    },
            ) { paddingValues ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) {
                    // 修复5：离线横幅已移到渐隐遮罩覆盖层之后组合（见下方），
                    // 避免横幅落在遮罩渐变高 alpha 区被"遮花"
                    // Message list area
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clearFocusOnTap()
                                .padding(bottom = messageListBottomInset),
                    ) {
                        // Message list - centered max-width 780dp
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    top = messageListTopPadding,
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            items(messages, key = { it.id }, contentType = { "message" }) { message ->
                                val isUser = message.role == "user"
                                val isLastMsg = message.id == lastMessageId
                                // 群聊按消息归属渲染说话方：AI 消息按 message.characterId 查参与者
                                // 映射（名字/颜色），查不到回落主角色（现状）；用户消息不需要查
                                // （气泡标签恒为"你"），流式占位气泡也保持主角色（见下方 StreamingSlot）
                                val speaker =
                                    if (isUser) {
                                        null
                                    } else {
                                        participantById[message.characterId] ?: primaryDisplayCharacter
                                    }
                                val speakerName = speaker?.name ?: "AI"
                                val speakerColor = participantColors[speaker?.id] ?: primaryCharacterColor
                                // 回调 remember 化：闭包引用稳定，配合稳定参数让 MessageBubble 可跳过重组。
                                // onCopy 额外以 content 为 key：swipe/重新生成后内容变化需重建闭包，避免复制到旧文本
                                val onCopy =
                                    remember(message.id, message.content) {
                                        { viewModel.copyMessage(message.content) }
                                    }
                                val onUndo =
                                    remember(message.id) { { viewModel.undoLastMessage() } }
                                val onBacktrack =
                                    remember(message.id) { { viewModel.backtrackToMessage(message.id) } }
                                val onRegenerate =
                                    remember(message.id) { { viewModel.regenerateMessage(message.id) } }
                                val onContinue =
                                    remember(message.id) { { viewModel.continueMessage() } }
                                val onSwipeLeft =
                                    remember(message.id) { { viewModel.swipeMessage(message.id, "left") } }
                                val onSwipeRight =
                                    remember(message.id) { { viewModel.swipeMessage(message.id, "right") } }
                                val isGreetingSlot = canSwapGreeting && message.id == greetingMessage?.id
                                if (isGreetingSlot) {
                                    // 开场白槽位：气泡下挂「切换开场白」入口（仅 greeting-only 会话）
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .widthIn(max = 780.dp)
                                                .padding(horizontal = 24.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            MessageBubble(
                                                message = message,
                                                characterName = speakerName,
                                                characterColor = speakerColor,
                                                // 修复6：传应用内三态深浅色，Markdown 颜色随之同步
                                                dark = isDark,
                                                isUser = isUser,
                                                isLastInGroup = true,
                                                isLastMessage = isLastMsg,
                                                onCopy = onCopy,
                                                onUndo = onUndo,
                                                onBacktrack = onBacktrack,
                                                onRegenerate = onRegenerate,
                                                onContinue = onContinue,
                                                onSwipeLeft = onSwipeLeft,
                                                onSwipeRight = onSwipeRight,
                                            )
                                        }
                                        AssistChip(
                                            onClick = { showGreetingSheet = true },
                                            label = { Text("切换开场白", style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .widthIn(max = 780.dp)
                                                .padding(horizontal = 24.dp),
                                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
                                    ) {
                                        MessageBubble(
                                            message = message,
                                            characterName = speakerName,
                                            characterColor = speakerColor,
                                            // 修复6：传应用内三态深浅色，Markdown 颜色随之同步
                                            dark = isDark,
                                            isUser = isUser,
                                            isLastInGroup = true,
                                            isLastMessage = isLastMsg,
                                            onCopy = onCopy,
                                            onUndo = onUndo,
                                            onBacktrack = onBacktrack,
                                            onRegenerate = onRegenerate,
                                            onContinue = onContinue,
                                            onSwipeLeft = onSwipeLeft,
                                            onSwipeRight = onSwipeRight,
                                        )
                                    }
                                }
                            }
                            if (isTyping) {
                                item(key = STREAMING_ITEM_KEY) {
                                    // 流式文本订阅下放到 StreamingSlot：
                                    // 每个流式增量只重组这一个 item，不整屏重组
                                    StreamingSlot(
                                        viewModel = viewModel,
                                        characterName = primaryDisplayCharacter?.name ?: "AI",
                                        // 修复4：跟随滚动所需的列表状态直接传参
                                        listState = listState,
                                        atBottom = atBottom,
                                        bottomAnchorIndex = bottomAnchorIndex,
                                        // 修复6：流式气泡的 Markdown 同样用应用内三态深浅色
                                        dark = isDark,
                                    )
                                }
                            }
                            item(key = BOTTOM_ANCHOR_ITEM_KEY) {
                                Spacer(modifier = Modifier.height(MessageToInputGap))
                            }
                        }
                    }
                    // 统一的上下渐隐覆盖层。它在消息列表之后绘制，保证遮罩始终可见；
                    // 底部预留区先铺实体背景，再叠加渐隐，遮罩结束后不会露出角色背景图。
                    ChatFadeOverlay(surfaceHeight = effectiveInputSurfaceHeight)
                    // 修复5：离线横幅移到渐隐遮罩覆盖层【之后】组合——Box 子级按声明顺序
                    // 绘制，后声明的横幅盖在遮罩之上，不再落在渐变高 alpha 区被遮花
                    if (!isOnline) {
                        Surface(
                            color = AccentRedLight,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "离线模式 — 消息将在联网后同步",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // 底部容器与顶部栏使用同一不透明背景，输入组件自身保持紧凑。
                    Surface(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    val measuredHeight = with(density) { coordinates.size.height.toDp() }
                                    if (measuredHeight > 0.dp && measuredHeight != inputSurfaceHeight) {
                                        inputSurfaceHeight = measuredHeight
                                    }
                                },
                        color = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .imePadding()
                                    .navigationBarsPadding()
                                    .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 回调 remember 化：闭包引用稳定（continueGroupChat 在 VM 内部自校验
                            // 群聊模式与生成中状态，调用时机安全）
                            val onContinueGroup = remember { { viewModel.continueGroupChat() } }
                            Column {
                                // 群聊模式：显示参与者快速选择栏
                                if (groupMode && participantCharacters.isNotEmpty()) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                                .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        // 提示文字
                                        Text(
                                            text = "参与者:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.align(Alignment.CenterVertically),
                                        )
                                        // 参与者头像列表
                                        participantCharacters.forEach { character ->
                                            Surface(
                                                onClick = {
                                                    // 待实现：点击头像 = 让该角色回复
                                                    // viewModel.continueGroupChat(targetCharacterId = character.id)
                                                },
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                modifier = Modifier.size(40.dp),
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize(),
                                                ) {
                                                    Text(
                                                        text = character.name.take(1),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                }
                                            }
                                        }
                                        // "继续对话"按钮
                                        OutlinedButton(
                                            onClick = onContinueGroup,
                                            enabled = !isTyping,
                                            modifier = Modifier.height(40.dp),
                                        ) {
                                            Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "继续",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                                MessageInput(
                                    onSend = { viewModel.sendMessage(it) },
                                    enabled = !isTyping,
                                    // 生成中：发送按钮变为"停止生成"按钮
                                    isGenerating = isTyping,
                                    onStop = { viewModel.stopGeneration() },
                                    // 传递参与者列表以支持 @ 提及
                                    participants = if (groupMode) participantCharacters else emptyList(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // 应用主题包：tokens 覆盖 scheme + 背景图铺底
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Chat has no navigation rail to absorb a side cutout, so the whole
                // route consumes only the current camera cutout area.
                .windowInsetsPadding(
                    WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                ),
    ) {
        if (bgBitmap != null) {
            Image(
                bitmap = bgBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        val effScheme =
            eff?.let { t ->
                baseScheme.copy(
                    primary = t.primary ?: baseScheme.primary,
                    onPrimary = t.onPrimary ?: baseScheme.onPrimary,
                    background = t.background ?: baseScheme.background,
                    onBackground = t.onBackground ?: baseScheme.onBackground,
                    surface = t.surface ?: baseScheme.surface,
                    onSurface = t.onSurface ?: baseScheme.onSurface,
                    surfaceVariant = t.surfaceVariant ?: baseScheme.surfaceVariant,
                )
            }
        // 恒定包裹（无 tokens 时传 baseScheme）：避免 tokens null↔非null 切换时
        // 组合树结构变化导致内部 remember 状态（如输入框文本）被丢弃
        MaterialTheme(colorScheme = effScheme ?: baseScheme) {
            chatContent()
        }
    }
    // 开场白切换抽屉：列出渲染后的开场白选项，选中项按消息正文匹配打勾，点选即原位替换
    if (showGreetingSheet && canSwapGreeting) {
        ModalBottomSheet(
            onDismissRequest = { showGreetingSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = "选择开场白",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(greetingOptions) { index, text ->
                    val isCurrent = text == greetingMessage?.content
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    greetingMessage?.let { viewModel.swapGreeting(it.id, index) }
                                    showGreetingSheet = false
                                }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = greetingOptionLabel(index),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color =
                                    if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        if (isCurrent) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.navigationBarsPadding().height(16.dp))
        }
    }
    // 提示词预览抽屉：逐条列出本次请求真实发出的 message（role + 来源 + 估算 token + 全文）
    if (showPromptSheet) {
        PromptTraceSheet(
            trace = promptTrace,
            requestParams = promptRequestParams,
            isLoading = isLoadingPromptTrace,
            error = promptTraceError,
            // 项目②：API 实测（最近一条带 usage 的助手消息；null = 网关未上报，显示「未上报」而非 0）
            apiPromptTokens = latestAssistantUsage?.promptTokens,
            apiCompletionTokens = latestAssistantUsage?.completionTokens,
            apiCacheHitRate = latestAssistantUsage?.cacheHitRateOrNull(),
            onCopyAll = { viewModel.copyPromptTraceText() },
            onDismiss = {
                showPromptSheet = false
                viewModel.clearPromptTrace()
            },
        )
    }
}

/**
 * 提示词预览底部抽屉。
 *
 * 内容与真实请求**同一次装配**（PromptBuilder.buildPromptTrace），因此这里看到的就是
 * 发给模型的那份，逐字节一致——不存在"预览一份、发送另一份"的漂移。
 *
 * 项目②：顶部「本地估算 vs API 实测」对照——本地估算来自 [PromptTrace.totalEstimatedTokens]
 * （与请求同源的估算口径），API 实测取最近一条助手消息的 prompt/completion tokens 与缓存命中率；
 * 网关未上报时显式标注「API 用量未上报」，不用 0 冒充。
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MaxLineLength", "MagicNumber")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptTraceSheet(
    trace: PromptTrace?,
    requestParams: List<Pair<String, String>>,
    isLoading: Boolean,
    error: String?,
    apiPromptTokens: Int? = null,
    apiCompletionTokens: Int? = null,
    apiCacheHitRate: Double? = null,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    var showFullText by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "本次提示词",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    trace?.let {
                        Text(
                            text =
                                "${it.segments.size} 条消息 · 约 ${it.totalEstimatedTokens} tokens" +
                                    if (it.truncationReasons.isNotEmpty()) " · 已裁剪" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 本地估算 vs API 实测（同一请求口径对照）
                        Text(
                            text =
                                if (apiPromptTokens != null) {
                                    "本地估算 ~${it.totalEstimatedTokens} tok · API 实测 prompt $apiPromptTokens" +
                                        (apiCompletionTokens?.let { c -> " / completion $c" } ?: "") +
                                        " · 缓存 " +
                                        (apiCacheHitRate?.let { r -> "${(r * 100).toInt()}%" } ?: "未知")
                                } else {
                                    "本地估算 ~${it.totalEstimatedTokens} tok · API 用量未上报"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (trace != null) {
                    TextButton(onClick = onCopyAll) { Text("复制全文") }
                }
            }
            if (trace != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !showFullText,
                        onClick = { showFullText = false },
                        label = { Text("按消息") },
                    )
                    FilterChip(
                        selected = showFullText,
                        onClick = { showFullText = true },
                        label = { Text("完整文本") },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
            }
            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
            }
            trace == null -> {
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (showFullText) {
                        item(key = "__full_text__") {
                            FullPromptTextBlock(trace = trace)
                        }
                    } else {
                        if (requestParams.isNotEmpty()) {
                            item(key = "__params__") {
                                RequestParamsSection(params = requestParams)
                            }
                        }
                        items(
                            count = trace.segments.size,
                            key = { index -> "prompt-${trace.segments[index].message.role}-$index" },
                            contentType = { "prompt-segment" },
                        ) { index ->
                            PromptSegmentCard(trace.segments[index], index + 1, trace.tokensOf(trace.segments[index]))
                        }
                    }
                    if (trace.truncationReasons.isNotEmpty() || trace.protocolConversionResults.isNotEmpty()) {
                        item(key = "__trace_meta__") {
                            TraceMetadataSection(trace)
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
                Spacer(modifier = Modifier.navigationBarsPadding().height(12.dp))
            }
        }
    }
}

/** 完整文本视图：按真实发送顺序展示 role、来源和全部正文。 */
@Suppress("FunctionNaming")
@Composable
private fun FullPromptTextBlock(trace: PromptTrace) {
    val fullText =
        remember(trace) {
            buildString {
                trace.segments.forEachIndexed { index, segment ->
                    if (index > 0) appendLine("\n")
                    appendLine("─── #${index + 1} [${segment.message.role}] ${segment.source} ───")
                    appendLine(segment.message.content)
                }
            }
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
    ) {
        SelectionContainer {
            Text(
                text = fullText,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 请求参数折叠区：本次请求实际生效的采样/请求参数。
 *
 * 取值与真实请求同源（`SettingsRepository.getLlmConfig` 解析后的结果），排查
 * "两次回复差别大"时先看这一栏。密钥只显示"是否已配置"，不显示内容。
 */
@Composable
private fun RequestParamsSection(params: List<Pair<String, String>>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "请求参数",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起请求参数" else "展开请求参数",
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                params.forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(132.dp),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 单条提示词消息卡：序号 + role 徽标 + 来源标签 + 估算 token + 可展开全文 */
@Composable
private fun PromptSegmentCard(
    segment: PromptBuilder.Segment,
    ordinal: Int,
    tokens: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    val roleColor =
        when (segment.message.role) {
            "user" -> MaterialTheme.colorScheme.tertiary
            "assistant" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondary
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$ordinal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Text(
                    text = segment.message.role,
                    style = MaterialTheme.typography.labelMedium,
                    color = roleColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = segment.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = "~$tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val metadata =
                listOfNotNull(
                    segment.activationReason?.let { "激活：$it" },
                    segment.insertionPosition?.let { "位置：$it" },
                    segment.truncationReason?.let { "裁剪：$it" },
                    segment.protocolConversionResult?.let { "协议：$it" },
                )
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = segment.message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun TraceMetadataSection(trace: PromptTrace) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            trace.truncationReasons.forEach { Text("裁剪：$it", style = MaterialTheme.typography.labelSmall) }
            trace.protocolConversionResults.forEach { Text("协议：$it", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** 开场白选项显示名：0 = 默认开场白（first_mes），其后为备用开场白 1..n */
private fun greetingOptionLabel(index: Int): String = if (index == 0) "默认开场白" else "备用开场白 $index"

/**
 * 流式占位子组合：内部自行订阅 streamingText，text 非空渲染流式气泡，
 * 否则显示打字指示器。独立子组合的目的：streamingText（已由 ViewModel
 * 做 ~80ms 时间窗节流）的每个增量只重组这一个 item，避免提升到顶层
 * 导致整个 ChatScreen 重组。isTyping 是低频状态，保留在顶层判断。
 */
@Composable
private fun StreamingSlot(
    viewModel: ChatViewModel,
    characterName: String,
    // 修复4：跟随滚动所需状态由调用方传入——本函数仍在列表 item 内组合，
    // 直接持有同一 listState 即可执行滚动，无需新的订阅
    listState: LazyListState,
    atBottom: Boolean,
    bottomAnchorIndex: Int,
    // 修复6：透传应用内三态深浅色给 Markdown 渲染
    dark: Boolean,
) {
    val text by viewModel.streamingText.collectAsStateWithLifecycle()
    // 修复4：流式增量使气泡持续增高，若用户仍贴底则跟随滚动到底部锚点，
    // 避免长回复的底部滚出屏幕；用户上翻阅读时不打扰。
    // key 为 text：每个流式增量重启本 effect，正好对应一次"增高后跟随"
    LaunchedEffect(text) {
        if (atBottom && !text.isNullOrEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }
    // 拷贝到普通局部变量：委托属性（by collectAsState）无法 smart cast
    when (val currentText = text) {
        null -> {
            // 首个 token 未到达：保持原打字指示器
            TypingIndicator(characterName)
        }
        else -> {
            // 流式输出中：显示累计文本的流式气泡
            StreamingBubble(text = currentText, characterName = characterName, dark = dark)
        }
    }
}

/**
 * 流式回复气泡：渲染累计文本 + 底部"生成中…"小字。
 * 每个增量都会更新 streamingText，remember(text) 让 Markdown 按 Latest 文本重新解析一次。
 */
@Composable
private fun StreamingBubble(
    text: String,
    characterName: String,
    // 修复6：应用内三态深浅色透传给 MarkdownRenderer
    dark: Boolean,
) {
    val (visibleText, thinking) = splitThinkingForDisplay(text)
    // 流式思维链直接可见；点击标题仍可收起，保证生成过程中也能确认模型正在返回 reasoning。
    var thinkingExpanded by remember { mutableStateOf(true) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 680.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (!thinking.isNullOrBlank()) {
                    thinkingSection(
                        thinking = thinking,
                        expanded = thinkingExpanded,
                        onExpandedChange = { thinkingExpanded = it },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                // 修复6：Markdown 颜色跟随应用内三态深浅色设置
                if (visibleText.isNotBlank()) {
                    MarkdownRenderer(content = visibleText, dark = dark)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "生成中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
