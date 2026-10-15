package com.mistbell.tavern.android.ui.chat

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.local.entity.ThemePackEntity
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.data.repository.ThemePackRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import com.mistbell.tavern.android.ui.components.CONTEXT_TOKEN_MAX
import com.mistbell.tavern.android.ui.components.CONTEXT_TOKEN_MIN
import com.mistbell.tavern.android.ui.components.ContextTokenLimitSelector
import com.mistbell.tavern.android.ui.components.formatTokenLimit
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val worldBookRepo = WorldBookRepository(application)
    private val themeRepo = ThemePackRepository(application)
    private val settingsRepo = SettingsRepository()
    private val db = TavernApplication.instance.container.database

    // 全局默认值（用于「全局默认：X」提示行）
    private val _globalDefaultContextTokens = MutableStateFlow(4096)
    val globalDefaultContextTokens: StateFlow<Int> = _globalDefaultContextTokens.asStateFlow()

    private val _globalDefaultLtmEnabled = MutableStateFlow(false)
    val globalDefaultLtmEnabled: StateFlow<Boolean> = _globalDefaultLtmEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            _globalDefaultContextTokens.value = settingsRepo.defaultContextTokens()
            _globalDefaultLtmEnabled.value = settingsRepo.defaultLtmEnabled()
        }
    }

    // 当前会话 ID（用于观察会话级主题）
    private val sessionIdFlow = MutableStateFlow<String?>(null)

    // 本会话专属主题 ID；"" = 未设置（跟随角色 / 全局）
    val sessionTheme: StateFlow<String> =
        sessionIdFlow
            .filterNotNull()
            .flatMapLatest { sessionId ->
                db.sessionDao().observeById(sessionId).map { it?.themeId ?: "" }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // 可选主题包列表
    val availableThemes: StateFlow<List<ThemePackEntity>> =
        themeRepo.observePacks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 可选 API 配置列表（数据源：api_configs 表）
    val apiConfigs: StateFlow<List<ApiConfigEntity>> =
        db.apiConfigDao()
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val characters: StateFlow<List<Character>> =
        db.characterDao()
            .getAll()
            .map { entities -> entities.map { it.toDomain() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val worldBooks: StateFlow<List<WorldBook>> =
        worldBookRepo.observeWorldBooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    private val _selectedModelId = MutableStateFlow("")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    // 会话级长期记忆三态：null = 跟随全局默认，true/false = 显式覆盖
    private val _enableLongTermMemory = MutableStateFlow<Boolean?>(null)
    val enableLongTermMemory: StateFlow<Boolean?> = _enableLongTermMemory.asStateFlow()

    // 会话级上下文长度三态：null = 跟随全局默认，具体值 = 显式覆盖
    private val _contextTokenLimit = MutableStateFlow<Int?>(null)
    val contextTokenLimit: StateFlow<Int?> = _contextTokenLimit.asStateFlow()

    private val _selectedCharacterIds = MutableStateFlow<List<String>>(emptyList())
    val selectedCharacterIds: StateFlow<List<String>> = _selectedCharacterIds.asStateFlow()

    // 选中的世界书 ID。"" = 无，具体 ID = 选中的世界书
    private val _selectedWorldBookId = MutableStateFlow<String>("")
    val selectedWorldBookId: StateFlow<String> = _selectedWorldBookId.asStateFlow()

    // 会话附加指令（author_note）；随会话实时回显
    val authorNote: StateFlow<String> =
        sessionIdFlow
            .filterNotNull()
            .flatMapLatest { sessionId ->
                db.sessionDao().observeById(sessionId).map { it?.authorNote ?: "" }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // 主角色默认世界书 ID（仅用于内部逻辑，UI 不展示）
    private val _characterDefaultWorldBookId = MutableStateFlow<String?>(null)
    val characterDefaultWorldBookId: StateFlow<String?> = _characterDefaultWorldBookId.asStateFlow()

    private var currentSessionId: String? = null
    private var mainCharacterId: String = ""

    fun loadSessionSettings(sessionId: String) {
        currentSessionId = sessionId
        sessionIdFlow.value = sessionId
        viewModelScope.launch {
            try {
                val session = db.sessionDao().getById(sessionId)
                if (session != null) {
                    mainCharacterId = session.characterId
                    _selectedProviderId.value = session.providerId.takeIf { it.isNotBlank() }
                    _selectedModelId.value = session.modelId
                    _enableLongTermMemory.value = session.enableLongTermMemory
                    _contextTokenLimit.value =
                        session.contextTokenLimit?.coerceIn(CONTEXT_TOKEN_MIN, CONTEXT_TOKEN_MAX)
                    _selectedCharacterIds.value = session.participantCharacterIds()
                    // 直接使用 session.worldBookId（空串 = "无"）
                    _selectedWorldBookId.value = session.worldBookId
                    _characterDefaultWorldBookId.value =
                        db.characterDao().getById(session.characterId)?.worldBookId?.takeIf { it.isNotBlank() }
                    android.util.Log.d(
                        "ChatSettings",
                        "Loaded session settings: providerId=${session.providerId}, modelId=${session.modelId}, " +
                            "enableLongTermMemory=${session.enableLongTermMemory}, " +
                            "contextTokenLimit=${session.contextTokenLimit}, worldBookId=${session.worldBookId}",
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatSettings", "Failed to load session settings", e)
            }
        }
    }

    fun setSelectedProvider(providerId: String?) {
        _selectedProviderId.value = providerId
        // 同时更新数据库中的会话设置
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        val provider = apiConfigs.value.find { it.id == providerId }
                        val modelId = provider?.model ?: ""
                        _selectedModelId.value = modelId
                        db.sessionDao().upsert(
                            session.copy(
                                providerId = providerId ?: "",
                                modelId = modelId,
                                updatedAt = java.time.Instant.now().toString(),
                            ),
                        )
                        android.util.Log.d("ChatSettings", "Updated session: providerId=$providerId, modelId=$modelId")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update session settings", e)
                }
            }
        }
    }

    // "" = 无世界书；具体 ID = 指定世界书
    fun setSelectedWorldBook(worldBookId: String) {
        _selectedWorldBookId.value = worldBookId
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                worldBookId = worldBookId,
                                updatedAt = java.time.Instant.now().toString(),
                            ),
                        )
                        android.util.Log.d(
                            "ChatSettings",
                            "Updated session worldBookId: ${if (worldBookId.isBlank()) "(none)" else worldBookId}",
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update world book setting", e)
                }
            }
        }
    }

    // 设置本会话专属主题；"" = 跟随角色 / 全局
    fun setSessionTheme(themeId: String) {
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId) ?: return@launch
                    // 用 DAO 定向更新，避免整体 upsert 覆盖其他字段
                    db.sessionDao().updateThemeId(
                        sessionId = sessionId,
                        ownerId = session.ownerId,
                        characterId = session.characterId,
                        themeId = themeId,
                    )
                    android.util.Log.d("ChatSettings", "Updated session themeId: ${if (themeId.isBlank()) "(follow)" else themeId}")
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update session theme", e)
                }
            }
        }
    }

    // 保存会话附加指令（定向更新 author_note，避免整体 upsert 覆盖其他字段）
    fun saveAuthorNote(text: String) {
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId) ?: return@launch
                    db.sessionDao().updateAuthorNote(
                        sessionId = sessionId,
                        ownerId = session.ownerId,
                        characterId = session.characterId,
                        note = text.trim(),
                    )
                    android.util.Log.d("ChatSettings", "Updated authorNote (${text.trim().length} chars)")
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update author note", e)
                }
            }
        }
    }

    // 三态设置：value = null 清除覆盖（跟随全局），true/false 显式写入；定向更新避免整体 upsert 覆盖其他字段
    fun setLongTermMemory(value: Boolean?) {
        _enableLongTermMemory.value = value
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().updateLtmEnabled(
                            sessionId = sessionId,
                            ownerId = session.ownerId,
                            characterId = session.characterId,
                            enabled = value,
                        )
                        android.util.Log.d("ChatSettings", "Updated enableLongTermMemory: $value")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update long term memory setting", e)
                }
            }
        }
    }

    // 三态设置：value = null 清除覆盖（跟随全局），具体值显式写入
    fun updateContextTokenLimit(value: Int?) {
        val normalized = value?.coerceIn(CONTEXT_TOKEN_MIN, CONTEXT_TOKEN_MAX)
        _contextTokenLimit.value = normalized
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().updateContextTokenLimit(
                            sessionId = sessionId,
                            ownerId = session.ownerId,
                            characterId = session.characterId,
                            tokenLimit = normalized,
                        )
                        android.util.Log.d("ChatSettings", "Updated contextTokenLimit: $normalized")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update context token limit", e)
                }
            }
        }
    }

    fun toggleParticipantCharacter(characterId: String) {
        val current = _selectedCharacterIds.value.toMutableList()
        if (current.contains(characterId)) {
            if (current.size <= 1) return
            current.remove(characterId)
        } else {
            if (current.size >= 4) return
            current.add(characterId)
        }
        saveParticipantCharacters(current)
    }

    fun makePrimaryCharacter(characterId: String) {
        val current = _selectedCharacterIds.value.toMutableList()
        if (!current.contains(characterId)) {
            if (current.size >= 4) return
            current.add(characterId)
        }
        current.remove(characterId)
        current.add(0, characterId)
        saveParticipantCharacters(current)
    }

    private fun saveParticipantCharacters(ids: List<String>) {
        val normalized =
            ids
                .ifEmpty { listOf(mainCharacterId) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
        _selectedCharacterIds.value = normalized
        currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                try {
                    val session = db.sessionDao().getById(sessionId)
                    if (session != null) {
                        db.sessionDao().upsert(
                            session.copy(
                                participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(normalized),
                                updatedAt = java.time.Instant.now().toString(),
                            ),
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSettings", "Failed to update participant characters", e)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit,
    onNavigateToMemoryManagement: () -> Unit,
    sessionId: String? = null,
    viewModel: ChatSettingsViewModel =
        viewModel(
            factory =
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
                    LocalContext.current.applicationContext as android.app.Application,
                ),
        ),
) {
    val apiConfigs by viewModel.apiConfigs.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val selectedProviderId by viewModel.selectedProviderId.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val selectedWorldBookId by viewModel.selectedWorldBookId.collectAsState()
    val sessionTheme by viewModel.sessionTheme.collectAsState()
    val availableThemes by viewModel.availableThemes.collectAsState()
    val characterDefaultWorldBookId by viewModel.characterDefaultWorldBookId.collectAsState()
    val enableLongTermMemory by viewModel.enableLongTermMemory.collectAsState()
    val contextTokenLimit by viewModel.contextTokenLimit.collectAsState()
    val globalDefaultContextTokens by viewModel.globalDefaultContextTokens.collectAsState()
    val globalDefaultLtmEnabled by viewModel.globalDefaultLtmEnabled.collectAsState()
    val selectedCharacterIds by viewModel.selectedCharacterIds.collectAsState()
    val authorNote by viewModel.authorNote.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }
    var showCharacterDialog by remember { mutableStateOf(false) }
    var showWorldBookDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    // 主题对话框中临时选中的主题 ID（"" = 跟随角色 / 全局）
    var selectedThemeChoice by remember { mutableStateOf("") }
    val selectedCharacters =
        remember(characters, selectedCharacterIds) {
            selectedCharacterIds.mapNotNull { id -> characters.find { it.id == id } }
        }

    // 加载当前会话的设置
    LaunchedEffect(sessionId) {
        sessionId?.let {
            viewModel.loadSessionSettings(it)
        }
    }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "返回",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Text(
                            text = "聊天设置",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "角色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showCharacterDialog = true },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositeCharacterAvatar(
                        characters = selectedCharacters,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前角色", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = selectedCharacters.joinToString("、") { it.name }.ifBlank { "未选择" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 模型设置
            Text(
                text = "模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showProviderDialog = true },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前模型", style = MaterialTheme.typography.bodySmall)
                        Text(
                            apiConfigs.find { it.id == selectedProviderId }?.let { provider ->
                                "${provider.name} - ${selectedModelId.ifBlank { provider.model }}"
                            } ?: "未配置",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 世界书设置
            Text(
                text = "世界书",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showWorldBookDialog = true },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前世界书", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text =
                                if (selectedWorldBookId.isBlank()) {
                                    "无"
                                } else {
                                    worldBooks.find { it.id == selectedWorldBookId }?.name ?: "未知世界书"
                                },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 主题设置（本会话专属主题）
            Text(
                text = "主题",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 打开对话框时同步当前会话主题为初始选中项
                            selectedThemeChoice = sessionTheme
                            showThemeDialog = true
                        },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前主题", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text =
                                availableThemes.find { it.id == sessionTheme }?.name
                                    ?: "跟随角色 / 全局",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 附加指令（会话级 author_note）
            Text(
                text = "附加指令",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            var authorNoteDraft by remember(authorNote) { mutableStateOf(authorNote) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text = "会话附加指令",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "注入在最近对话之后、你的消息之前",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authorNoteDraft,
                        onValueChange = { authorNoteDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "可选。注入在最近对话之后、你的消息之前，支持 {{char}} 等宏",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        minLines = 3,
                        maxLines = 8,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        // 清空草稿并保存空值 = 清除附加指令
                        TextButton(
                            onClick = {
                                authorNoteDraft = ""
                                viewModel.saveAuthorNote("")
                            },
                            enabled = authorNote.isNotBlank() || authorNoteDraft.isNotBlank(),
                        ) {
                            Text("清除")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.saveAuthorNote(authorNoteDraft) },
                            enabled = authorNoteDraft.trim() != authorNote,
                        ) {
                            Text("保存")
                        }
                    }
                }
            }

            // 记忆
            Text(
                text = "记忆",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // 记忆管理
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToMemoryManagement() },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "记忆管理",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "查看和编辑结构化记忆",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 长期记忆三态：跟随全局 / 开启 / 关闭
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text = "长期记忆",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "保存对话内容到长期记忆",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text =
                            if (enableLongTermMemory == null) {
                                "跟随全局（当前：${if (globalDefaultLtmEnabled) "开" else "关"}）"
                            } else {
                                "当前生效：${if (enableLongTermMemory == true) "开" else "关"}"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = enableLongTermMemory == null,
                            onClick = { viewModel.setLongTermMemory(null) },
                            label = { Text("跟随全局") },
                        )
                        FilterChip(
                            selected = enableLongTermMemory == true,
                            onClick = { viewModel.setLongTermMemory(true) },
                            label = { Text("开启") },
                        )
                        FilterChip(
                            selected = enableLongTermMemory == false,
                            onClick = { viewModel.setLongTermMemory(false) },
                            label = { Text("关闭") },
                        )
                    }
                }
            }

            // 上下文长度
            Text(
                text = "高级",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text = "上下文长度",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "控制 AI 能记住多少对话历史",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            if (contextTokenLimit == null) {
                                "跟随全局（当前：${formatTokenLimit(globalDefaultContextTokens)}）"
                            } else {
                                formatTokenLimit(contextTokenLimit ?: globalDefaultContextTokens)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "全局默认：${formatTokenLimit(globalDefaultContextTokens)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ContextTokenLimitSelector(
                        value = contextTokenLimit,
                        onValueChange = { viewModel.updateContextTokenLimit(it) },
                        followGlobalEffective = globalDefaultContextTokens,
                    )
                }
            }
        }
    }

    if (showCharacterDialog) {
        AlertDialog(
            onDismissRequest = { showCharacterDialog = false },
            title = { Text("选择聊天角色") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "可选择 1-4 个角色；第一位作为主角色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    characters.forEach { character ->
                        val selected = selectedCharacterIds.contains(character.id)
                        val isPrimary = selectedCharacterIds.firstOrNull() == character.id
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleParticipantCharacter(character.id) },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (selected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                ),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompositeCharacterAvatar(
                                    characters = listOf(character),
                                    modifier = Modifier.size(36.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(character.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        text =
                                            if (isPrimary) {
                                                "主角色"
                                            } else if (selected) {
                                                "参与聊天"
                                            } else {
                                                "点击加入"
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (selected && !isPrimary) {
                                    TextButton(onClick = { viewModel.makePrimaryCharacter(character.id) }) {
                                        Text("设为主")
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCharacterDialog = false }) {
                    Text("完成")
                }
            },
        )
    }

    // 提供商选择对话框
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("选择模型提供商") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (apiConfigs.isEmpty()) {
                        Text(
                            "暂无提供商，请先在设置中添加",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        apiConfigs.forEach { provider ->
                            Card(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.setSelectedProvider(provider.id)
                                            showProviderDialog = false
                                        },
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            provider.name,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        if (provider.model.isNotBlank()) {
                                            Text(
                                                "模型: ${provider.model}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (selectedProviderId == provider.id) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderDialog = false }) {
                    Text("关闭")
                }
            },
        )
    }

    // 世界书选择对话框
    if (showWorldBookDialog) {
        AlertDialog(
            onDismissRequest = { showWorldBookDialog = false },
            title = { Text("选择世界书") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // "无" 选项
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setSelectedWorldBook("")
                                    showWorldBookDialog = false
                                },
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("无", fontWeight = FontWeight.Medium)
                            if (selectedWorldBookId.isBlank()) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    // 世界书列表
                    worldBooks.forEach { book ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setSelectedWorldBook(book.id)
                                        showWorldBookDialog = false
                                    },
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(book.name.ifBlank { "未命名世界书" }, fontWeight = FontWeight.Medium)
                                if (selectedWorldBookId == book.id) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWorldBookDialog = false }) {
                    Text("关闭")
                }
            },
        )
    }

    // 主题选择对话框（本会话专属主题；单选）
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题") },
            text = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 第一项：跟随角色 / 全局（themeId = ""）
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedThemeChoice = "" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedThemeChoice.isBlank(),
                            onClick = { selectedThemeChoice = "" },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("跟随角色 / 全局", fontWeight = FontWeight.Medium)
                    }
                    // 可用主题包列表
                    availableThemes.forEach { theme ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedThemeChoice = theme.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedThemeChoice == theme.id,
                                onClick = { selectedThemeChoice = theme.id },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                theme.name.ifBlank { "未命名主题" },
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setSessionTheme(selectedThemeChoice)
                        showThemeDialog = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}
