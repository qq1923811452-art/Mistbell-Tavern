package com.mistbell.tavern.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_CLASSIC
import com.mistbell.tavern.android.data.api.model.SESSION_MODE_GROUP
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.local.entity.ApiConfigEntity
import com.mistbell.tavern.android.data.local.entity.MessageEntity
import com.mistbell.tavern.android.data.local.entity.SessionEntity
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.data.repository.WorldBookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

// 群聊模式角色数量限制
// internal：ChatSetupScreen 的模式提示文案复用
internal const val MIN_GROUP_CHARACTERS = 2
internal const val MAX_SELECTABLE_CHARACTERS = 4

class ChatSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TavernApplication.instance.container.database
    private val settingsRepo = SettingsRepository()
    private val worldBookRepo = WorldBookRepository(application)

    // 所有角色
    val characters: StateFlow<List<Character>> =
        db.characterDao()
            .getAll()
            .map { entities -> entities.map { it.toDomain() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有提供商（数据源：api_configs 表）
    val apiConfigs: StateFlow<List<ApiConfigEntity>> =
        db.apiConfigDao()
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 所有世界书
    val worldBooks: StateFlow<List<WorldBook>> =
        worldBookRepo.observeWorldBooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 选中的角色 IDs
    private val _selectedCharacterIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedCharacterIds: StateFlow<Set<String>> = _selectedCharacterIds.asStateFlow()

    // 聊天模式："classic"（经典，默认）| "group"（群聊，多角色轮流回应）。建会话时写入 session.mode
    private val _mode = MutableStateFlow(SESSION_MODE_CLASSIC)
    val mode: StateFlow<String> = _mode.asStateFlow()

    // 选中的提供商 ID
    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    // 选中的世界书 ID。"" = 无，具体 ID = 选中的世界书
    private val _selectedWorldBookId = MutableStateFlow<String>("")
    val selectedWorldBookId: StateFlow<String> = _selectedWorldBookId.asStateFlow()

    // 标记用户是否手动选择过世界书
    private var hasUserSelectedWorldBook = false

    // 角色卡默认世界书 ID（多角色取首个有 worldBookId 的角色）；随选中角色变化。
    val characterDefaultWorldBookId: StateFlow<String?> =
        combine(_selectedCharacterIds, characters) { ids, chars ->
            ids.firstNotNullOfOrNull { id ->
                chars.find { it.id == id }?.worldBookId?.takeIf { wb -> wb.isNotBlank() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 长期记忆三态：null = 跟随全局默认，true/false = 显式覆盖（必须声明在 init 块之前——
    // Kotlin 属性按声明顺序初始化）
    private val _enableLongTermMemory = MutableStateFlow<Boolean?>(null)
    val enableLongTermMemory: StateFlow<Boolean?> = _enableLongTermMemory.asStateFlow()

    // 全局默认长期记忆（展示"跟随全局"时的当前生效值）
    private val _globalDefaultLtmEnabled = MutableStateFlow(false)
    val globalDefaultLtmEnabled: StateFlow<Boolean> = _globalDefaultLtmEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            _globalDefaultLtmEnabled.value = settingsRepo.defaultLtmEnabled()
        }
        // 当角色选择变化时，自动更新世界书默认值（仅在用户未手动选择时）
        viewModelScope.launch {
            characterDefaultWorldBookId.collect { defaultId ->
                if (!hasUserSelectedWorldBook) {
                    _selectedWorldBookId.value = defaultId ?: ""
                }
            }
        }
    }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // 初始化：从初始角色 ID 设置
    fun initialize(initialCharacterId: String?) {
        if (initialCharacterId != null && _selectedCharacterIds.value.isEmpty()) {
            _selectedCharacterIds.value = setOf(initialCharacterId)
        }
    }

    fun toggleCharacter(characterId: String) {
        val before = _selectedCharacterIds.value
        // 收敛逻辑抽为顶层纯函数 resolveCharacterSelection（便于 JVM 单测）：
        // 经典模式 = 「含且仅含该角色则取消，否则收敛为单选该角色」，绝不再走 toggle-off 分支
        val after = resolveCharacterSelection(before, characterId, _mode.value)
        if (after == before) {
            // 选中集无变化只会出现在群聊模式超上限时：提示并维持原选中集；
            // 经典模式的收敛/取消必然改变选中集，不会走到这里
            if (_mode.value != SESSION_MODE_CLASSIC && characterId !in before) {
                showToast("最多选择 $MAX_SELECTABLE_CHARACTERS 个角色")
            }
            return
        }
        _selectedCharacterIds.value = after
    }

    // 切换聊天模式：回到经典模式时若当前多选，自动收敛为最后选中的一个角色
    fun setMode(mode: String) {
        _mode.value = if (mode == SESSION_MODE_GROUP) SESSION_MODE_GROUP else SESSION_MODE_CLASSIC
        val selected = _selectedCharacterIds.value
        if (_mode.value == SESSION_MODE_CLASSIC && selected.size > 1) {
            _selectedCharacterIds.value = setOf(selected.last())
        }
    }

    fun setSelectedProvider(providerId: String?) {
        _selectedProviderId.value = providerId
    }

    // "" = 无世界书；具体 ID = 用户手动指定。
    fun setSelectedWorldBook(worldBookId: String) {
        hasUserSelectedWorldBook = true
        _selectedWorldBookId.value = worldBookId
    }

    // 三态设置：null = 清除覆盖、跟随全局默认；true/false = 显式写入
    fun setLongTermMemory(value: Boolean?) {
        _enableLongTermMemory.value = value
    }

    fun canStartChat(): Boolean {
        return _selectedCharacterIds.value.isNotEmpty()
    }

    // 创建页永远创建新会话；旧会话从最近聊天列表进入。
    suspend fun getOrCreateSession(
        characterIds: Set<String>,
        ownerId: String = "local-user",
    ): String {
        if (characterIds.isEmpty()) return "new"

        val characterId = characterIds.first() // 使用第一个角色作为主角色

        // 建会话写入所选聊天模式（契约：SessionEntity.mode/modeConfigJson，默认 classic）
        return createNewSession(characterId, characterIds, ownerId, _mode.value)
    }

    private suspend fun createNewSession(
        characterId: String,
        characterIds: Set<String>,
        ownerId: String,
        mode: String,
    ): String {
        android.util.Log.d("ChatSetup", "Creating new session for character $characterId")
        val sessionId = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        // 获取选中的 provider 信息，如果未选择则使用第一个可用的
        var selectedProvider = _selectedProviderId.value
        if (selectedProvider.isNullOrBlank()) {
            selectedProvider = apiConfigs.value.firstOrNull()?.id ?: ""
            android.util.Log.d("ChatSetup", "No provider selected, using first available: $selectedProvider")
        }

        val selectedModel = apiConfigs.value.find { it.id == selectedProvider }?.model ?: ""
        android.util.Log.d("ChatSetup", "Creating session with providerId: $selectedProvider, modelId: $selectedModel")

        // 世界书 ID 直接使用选中值
        val sessionWorldBookId = _selectedWorldBookId.value

        val session =
            SessionEntity(
                id = sessionId,
                ownerId = ownerId,
                characterId = characterId,
                title = "新对话",
                createdAt = now,
                updatedAt = now,
                messageCount = 0,
                providerId = selectedProvider,
                modelId = selectedModel,
                worldBookId = sessionWorldBookId,
                summaryJson = "",
                unreadCount = 0,
                isPinned = false,
                pinnedAt = null,
                isMuted = false,
                // 长期记忆：null = 跟随全局（用户在创建页未显式选择时）；显式 true/false 才写入覆盖
                enableLongTermMemory = _enableLongTermMemory.value,
                // 上下文长度：创建页无控件，一律留 null 跟随全局默认
                participantCharacterIdsJson = SessionEntity.encodeParticipantCharacterIds(characterIds),
                // 聊天模式：classic | group（v17 迁移列，NOT NULL DEFAULT 'classic'）；
                // mode_config_json 本批无配置内容，固定空串
                mode = mode,
                modeConfigJson = "",
            )

        db.sessionDao().upsert(session)

        // 插入角色的开场白：默认开场白 = first_mes，为空时由首个备用开场白顶上
        // （buildGreetingOptions 的首项）；切换开场白在聊天界面进行（ChatViewModel.swapGreeting）
        val characterEntity = db.characterDao().getById(characterId)
        android.util.Log.d(
            "ChatSetup",
            "Character loaded: ${characterEntity?.name}, greetingLength=${characterEntity?.firstMes?.length ?: 0}",
        )

        val greetingText =
            characterEntity?.let { entity ->
                buildGreetingOptions(
                    entity.firstMes,
                    entity.toDomain().data?.alternateGreetings.orEmpty(),
                ).firstOrNull()
            }.orEmpty()

        if (characterEntity != null && greetingText.isNotBlank()) {
            // F2.1：开场白先用宏引擎渲染（{{char}}/{{user}} 等），用户名与人设取全局设置
            val mctx =
                com.mistbell.tavern.android.util.MacroContext(
                    char = characterEntity.name,
                    user = ChatSettingsResolver.userName(db.settingsDao().getValue(ChatSettingsResolver.KEY_USER_NAME)),
                    description = characterEntity.description,
                    personality = characterEntity.personality,
                    scenario = characterEntity.scenario,
                    persona = ChatSettingsResolver.userPersona(db.settingsDao().getValue(ChatSettingsResolver.KEY_USER_PERSONA)),
                )
            val firstMessage =
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    ownerId = ownerId,
                    characterId = characterId,
                    role = "assistant",
                    content = com.mistbell.tavern.android.util.MacroEngine.render(greetingText, mctx),
                    thinking = null,
                    createdAt = now,
                    memoryIdsJson = "[]",
                    swipesJson = "[]",
                    swipeIndex = 0,
                    thinkingSwipesJson = "[]",
                    isRead = true,
                )
            db.messageDao().upsert(firstMessage)
            android.util.Log.d("ChatSetup", "Greeting inserted: length=${greetingText.length}")

            // 更新会话消息数
            db.sessionDao().upsert(session.copy(messageCount = 1, updatedAt = now))
        } else {
            android.util.Log.d("ChatSetup", "No greeting to insert")
        }
        return sessionId
    }

    fun showToast(message: String) {
        _toast.value = message
    }

    fun clearToast() {
        _toast.value = null
    }
}

// 角色点选收敛纯函数（便于单测，无 Android 依赖）：输入当前选中集、目标角色 id、聊天模式，
// 输出新的选中集。ViewModel 依赖 Application（AndroidViewModel）无法 JVM 测，收敛规则全部收敛在此。
//
// 经典模式（契约 6 B'）："已选集恰为 {该角色} 则清空，否则收敛为 {该角色}"——
// 修复旧实现的自相矛盾：点新角色先被替换成单选、再被 toggle-off 分支删掉，导致清空且无法再选；
// 新逻辑下经典模式绝不再进入 toggle-off 分支。
//
// 群聊模式：保持既有语义——多选 toggle（已选则移除，未选则加入）+ 上限 MAX_SELECTABLE_CHARACTERS；
// 超上限时原样返回当前集合（拒绝），由 ViewModel 对比前后集合差异并弹出提示。
internal fun resolveCharacterSelection(
    current: Set<String>,
    targetId: String,
    mode: String,
): Set<String> =
    if (mode == SESSION_MODE_CLASSIC) {
        // 经典模式：唯一选中就是它 → 取消（清空）；否则无论已选什么，收敛为单选该角色
        if (current == setOf(targetId)) emptySet() else setOf(targetId)
    } else {
        when {
            // 群聊多选：已选中 → 移除（toggle-off）
            targetId in current -> current - targetId
            // 群聊上限：已满 4 个且目标未选中 → 拒绝（原样返回，无变化）
            current.size >= MAX_SELECTABLE_CHARACTERS -> current
            // 群聊多选：未选中且未满 → 加入
            else -> current + targetId
        }
    }

// 开场白选项构建纯函数（便于单测）：first_mes 缺省为首条「默认开场白」，
// 其后跟备用开场白（酒馆 alternate_greetings）；空白项一律过滤。
// 首条为空且存在备用时，备用顶上成为首条（此前这种情况建会话不插任何开场白）
internal fun buildGreetingOptions(
    firstMes: String,
    alternateGreetings: List<String>,
): List<String> = listOf(firstMes).filter { it.isNotBlank() } + alternateGreetings.filter { it.isNotBlank() }
