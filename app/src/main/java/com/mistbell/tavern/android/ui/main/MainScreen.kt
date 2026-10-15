package com.mistbell.tavern.android.ui.main

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.ui.character.CharacterListScreen
import com.mistbell.tavern.android.ui.chatlist.ChatListScreen
import com.mistbell.tavern.android.ui.settings.ModernSettingsScreen
import com.mistbell.tavern.android.ui.settings.SettingsViewModel
import com.mistbell.tavern.android.ui.worldbook.WorldBookListScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 底部导航栏/侧边导航栏共用的目的地定义 */
private data class MainDestination(
    val label: String,
    val icon: ImageVector,
)

private val mainDestinations =
    listOf(
        MainDestination("聊天", Icons.Default.Chat),
        MainDestination("角色", Icons.Default.Person),
        MainDestination("世界书", Icons.Default.Book),
        MainDestination("设置", Icons.Default.Settings),
    )

@OptIn(
    ExperimentalMaterial3Api::class,
    // calculateWindowSizeClass 为实验 API
    androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class,
)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Compose 屏幕级组件的既有形态（原由 detekt 基线吸收）
@Composable
fun MainScreen(
    onChatClick: (sessionId: String, characterId: String) -> Unit,
    onNewChatClick: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onNewCharacter: () -> Unit,
    onNavigateToPromptManagement: () -> Unit = {},
    onNavigateToWorldBookDetail: (String) -> Unit,
    onNavigateToChatSetup: (String) -> Unit,
    onNavigateToVersionChangelog: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToThemeManager: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val app = LocalContext.current.applicationContext as android.app.Application
    val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    val scope = rememberCoroutineScope()

    // 自适应：宽度非 Compact（横屏手机/平板/折叠屏展开）时用左侧 NavigationRail，
    // 竖屏手机维持底部 NavigationBar
    val windowSizeClass = calculateWindowSizeClass(LocalContext.current as Activity)
    val useNavigationRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    // tab 内容（bottomPadding：底部导航占用的高度；Rail 模式下为 0）。
    // 直接切换当前页面，避免 AnimatedContent 在首帧同时建立动画测量层。
    val tabContent: @Composable (Modifier) -> Unit = { contentModifier ->
        when (selectedTab) {
            0 ->
                ChatListScreen(
                    onChatClick = onChatClick,
                    onNewChatClick = onNewChatClick,
                    modifier = contentModifier,
                )
            1 ->
                CharacterListScreen(
                    onCharacterClick = { character ->
                        scope.launch {
                            val latestSession =
                                withContext(Dispatchers.IO) {
                                    (app as TavernApplication).container.database.sessionDao()
                                        .getLatestByCharacter("local-user", character.id)
                                }

                            if (latestSession != null) {
                                android.util.Log.d(
                                    "MainScreen",
                                    "Opening latest session: ${latestSession.id} for character: ${character.id}",
                                )
                                onChatClick(latestSession.id, character.id)
                            } else {
                                android.util.Log.d("MainScreen", "No session, navigating to chat setup for character: ${character.id}")
                                onNavigateToChatSetup(character.id)
                            }
                        }
                    },
                    onEditCharacter = onEditCharacter,
                    onNewCharacter = onNewCharacter,
                    showTopBarBackButton = false,
                    modifier = contentModifier,
                )
            2 ->
                WorldBookListScreen(
                    showBackButton = false,
                    onBookClick = { bookId -> onNavigateToWorldBookDetail(bookId) },
                    modifier = contentModifier,
                )
            3 -> {
                val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                ModernSettingsScreen(
                    onBack = null,
                    onNavigateToThemeManager = onNavigateToThemeManager,
                    onNavigateToPromptManagement = onNavigateToPromptManagement,
                    onNavigateToVersionChangelog = onNavigateToVersionChangelog,
                    onNavigateToAbout = onNavigateToAbout,
                    viewModel = settingsViewModel,
                    modifier = contentModifier,
                )
            }
        }
    }

    if (useNavigationRail) {
        // 横屏导航栏只避让真正位于起始边的前摄安全区，避免内容被遮挡。
        // 内容区单独消费末端安全区，避免把导航栏宽度重复算进空白。
        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            NavigationRail(
                modifier =
                    Modifier
                        // Keep the rail outside a left-side camera cutout, but keep the
                        // rail itself compact so the cutout and rail width do not create
                        // an unnecessarily large blank column.
                        .windowInsetsPadding(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Start),
                        )
                        .width(72.dp)
                        .padding(vertical = 8.dp),
                // The rail owns vertical system-bar handling; the explicit horizontal
                // cutout padding above is applied only when a left-side cutout exists.
                windowInsets = NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Vertical),
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, androidx.compose.ui.Alignment.Top),
                ) {
                    mainDestinations.forEachIndexed { index, destination ->
                        NavigationRailItem(
                            modifier = Modifier.width(64.dp).height(68.dp),
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                            colors =
                                NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                }
            }
            tabContent(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.displayCutout.only(WindowInsetsSides.End),
                    ),
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {
                    mainDestinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                }
            },
        ) { paddingValues ->
            tabContent(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding()),
            )
        }
    }
}
