package com.mistbell.tavern.android.ui.settings

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

// 无限循环 carousel 的页数放大倍数：N 张卡 → N×500 页，双向滚动均不到边界
private const val CIRCULAR_PAGE_FACTOR = 500

// 预览 Dialog 的 blurBehind 半径（WindowManager 限制 0..150，API 31+ 生效）。
// 取小值：半径过大把背后设置页的轮廓全抹掉，用户要求能辨认轮廓
private const val PREVIEW_BLUR_BEHIND_RADIUS = 18

// 预览 Dialog 背景透明度：越低越透（能越清楚看到背后的设置页）；
// 0.4 时背后轮廓可辨且网格卡片文字仍可读
private const val PREVIEW_BG_ALPHA = 0.4f

// 预览顶栏与网格卡片透明度（与背景同档，整体一致半透明）
private const val PREVIEW_CONTENT_ALPHA = 0.7f

/**
 * 设置页内联的 API 卡片管理区。
 *
 * - 左右滑动（HorizontalPager）切换配置卡片，与设置页垂直滚动无手势冲突；
 * - 长按当前卡片进入全屏预览（所有配置网格）；
 * - 添加/编辑走 [ApiConfigEditorDialog]，测试/删除直连 [ApiConfigViewModel]。
 *
 * 原独立页 StackedApiManagerScreen（垂直滑动版）已删除，此为唯一入口。
 */
@Suppress("FunctionNaming", "LongMethod", "MagicNumber") // coerceIn 范围为堆叠视觉设计常量
@Composable
fun InlineApiCardsSection(
    viewModel: ApiConfigViewModel,
    modifier: Modifier = Modifier,
) {
    val apiConfigs by viewModel.apiConfigs.collectAsState()
    val currentIndex by viewModel.currentApiIndex.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (apiConfigs.isEmpty()) {
            EmptyInlineApiState(onAdd = { showAddDialog = true })
        } else {
            // 无限循环：页数放大为 N×CIRCULAR_PAGE_FACTOR，真实索引 = page % N。
            // 起始页对齐到中间圈的 currentIndex，双向都有充足页数可滚。
            val pageCount = apiConfigs.size * CIRCULAR_PAGE_FACTOR
            val middlePage = pageCount / 2
            val startPage = middlePage - (middlePage % apiConfigs.size) + currentIndex.coerceIn(0, apiConfigs.size - 1)
            val pagerState = rememberPagerState(initialPage = startPage) { pageCount }
            val scope = rememberCoroutineScope()

            // 外部索引变化（预览点选/默认配置变更）→ 滚到当前圈的目标页（mod 相同的最近页）
            LaunchedEffect(currentIndex) {
                val target =
                    (pagerState.currentPage / apiConfigs.size) * apiConfigs.size +
                        currentIndex.coerceIn(0, apiConfigs.size - 1)
                if (pagerState.currentPage % apiConfigs.size != currentIndex &&
                    target in 0 until pageCount
                ) {
                    pagerState.animateScrollToPage(target)
                }
            }
            // 滑动切换 → 回写真实索引（mod 映射）
            LaunchedEffect(pagerState.currentPage) {
                val realIndex = pagerState.currentPage % apiConfigs.size
                if (realIndex != currentIndex) {
                    viewModel.setCurrentApiIndex(realIndex)
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    // 左右各露出相邻卡片：三卡 carousel（中间在前、两侧在后）
                    contentPadding = PaddingValues(horizontal = 56.dp),
                    pageSpacing = 4.dp,
                ) { page ->
                    val pageOffset =
                        (page - pagerState.currentPage - pagerState.currentPageOffsetFraction)
                            .coerceIn(-1.5f, 1.5f)
                    val realIndex = page % apiConfigs.size
                    InlineApiCard(
                        apiConfig = apiConfigs[realIndex],
                        pageOffset = pageOffset,
                        isCurrentCard = page == pagerState.currentPage,
                        onClick = {
                            if (page != pagerState.currentPage) {
                                scope.launch { pagerState.animateScrollToPage(page) }
                            }
                        },
                        onLongPress = { showPreview = true },
                        onTest = { viewModel.testConnection(apiConfigs[realIndex]) },
                        modifier =
                            Modifier
                                .zIndex(1f - kotlin.math.abs(pageOffset)),
                    )
                }

                // 指示器 + 添加按钮（卡片下方独立一行，不与卡内按钮重叠）
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardIndicator(
                        totalCards = apiConfigs.size,
                        currentIndex = pagerState.currentPage % apiConfigs.size,
                    )
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加配置",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // 长按 → 全屏预览（所有配置网格）
    if (showPreview && apiConfigs.isNotEmpty()) {
        ApiPreviewDialog(
            apiConfigs = apiConfigs,
            selectedIndex = currentIndex,
            onSelect = { index ->
                showPreview = false
                viewModel.setCurrentApiIndex(index)
            },
            onEdit = { config -> editingConfig = config },
            onDelete = { id -> viewModel.deleteApiConfig(id) },
            onDismiss = { showPreview = false },
        )
    }

    // 添加对话框
    if (showAddDialog) {
        ApiConfigEditorDialog(
            apiConfig = null,
            viewModel = viewModel,
            onDismiss = { showAddDialog = false },
            onConfirm = {
                name,
                apiUrl,
                apiKey,
                model,
                setAsDefault,
                type,
                context1M,
                streamingEnabled,
                thinkingDisabled ->
                viewModel.addApiConfig(
                    name = name,
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    model = model,
                    setAsDefault = setAsDefault,
                    type = type,
                    context1M = context1M,
                    streamingEnabled = streamingEnabled,
                    thinkingDisabled = thinkingDisabled,
                )
                showAddDialog = false
            },
        )
    }

    // 编辑对话框
    editingConfig?.let { config ->
        ApiConfigEditorDialog(
            apiConfig = config,
            viewModel = viewModel,
            onDismiss = { editingConfig = null },
            onConfirm = {
                name,
                apiUrl,
                apiKey,
                model,
                setAsDefault,
                type,
                context1M,
                streamingEnabled,
                thinkingDisabled ->
                viewModel.updateApiConfig(
                    config.copy(
                        name = name,
                        apiUrl = apiUrl,
                        apiKey = apiKey,
                        model = model,
                        isDefault = setAsDefault || config.isDefault,
                        type = type,
                        context1M = context1M,
                        streamingEnabled = streamingEnabled,
                        thinkingDisabled = thinkingDisabled,
                    ),
                )
                if (setAsDefault) {
                    viewModel.setDefaultApiConfig(config.id)
                }
                editingConfig = null
            },
        )
    }
}

/**
 * 内联单张 API 卡片（三卡 carousel：中间完整在前，左右缩小半透在后）。
 * pageOffset 由 Pager 实时驱动，无需额外动画即可跟手；
 * 点击/长按经 combinedClickable，涟漪从按下的位置扩散。
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber")
@Composable
private fun InlineApiCard(
    apiConfig: ApiConfig,
    pageOffset: Float,
    isCurrentCard: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val absOffset = kotlin.math.abs(pageOffset).coerceIn(0f, 1.5f)
    val scale = 1f - (0.16f * absOffset).coerceAtMost(0.32f)
    val alpha = 1f - (0.45f * absOffset).coerceAtMost(0.7f)

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .height(160.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                ),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (isCurrentCard) 8.dp else 4.dp,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            brush =
                                Brush.verticalGradient(
                                    // 低饱和 surface 容器渐变：与设置页分组卡片同色系，
                                    // 避免高饱和彩色卡片在深色系中突兀
                                    colors =
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ),
                                ),
                        ),
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶部：名称、默认标记与最近测试状态
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = apiConfig.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        if (apiConfig.isDefault) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Text(
                                        text = "默认",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }

                    // 模型
                    Text(
                        text = apiConfig.model,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // 最近一次连接测试状态
                    ConnectionStatusChip(status = apiConfig.lastTestStatus)
                }

                // 底部：测试连接
                FilledTonalButton(
                    onClick = onTest,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isCurrentCard,
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("测试连接")
                }
            }
        }
    }
}

/**
 * 长按触发的全屏预览：所有 API 配置网格。
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun ApiPreviewDialog(
    apiConfigs: List<ApiConfig>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onEdit: (ApiConfig) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true,
            ),
    ) {
        // API 31+：窗口级背景模糊（blurBehind 模糊 Dialog 背后的主窗口内容）；
        // 低版本退化为半透明背景。radius 取值范围 0..150（WindowManager 限制）
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    attributes.blurBehindRadius = PREVIEW_BLUR_BEHIND_RADIUS
                    attributes = attributes
                }
            }
        }

        Scaffold(
            // 半透明底：配合 blurBehind 呈现"毛玻璃"层次；无模糊的低版本仍有可读蒙层
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = PREVIEW_BG_ALPHA),
            topBar = {
                ApiPreviewTopBar(onClose = onDismiss)
            },
        ) { paddingValues ->
            OverviewGridView(
                apiConfigs = apiConfigs,
                selectedIndex = selectedIndex,
                onCardClick = { index -> onSelect(index) },
                onEditApi = onEdit,
                onDeleteApi = onDelete,
                cardAlpha = PREVIEW_CONTENT_ALPHA,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
private fun ApiPreviewTopBar(onClose: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "所有配置",
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = PREVIEW_CONTENT_ALPHA),
            ),
    )
}

/**
 * 内联空状态（设置页上下文，非全屏）
 */
@Suppress("FunctionNaming")
@Composable
private fun EmptyInlineApiState(onAdd: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "还没有 API 配置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "添加你的第一个 API 配置开始使用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加配置")
        }
    }
}
