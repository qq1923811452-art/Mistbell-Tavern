package com.mistbell.tavern.android.ui.chatlist

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.data.api.model.Message
import com.mistbell.tavern.android.data.api.model.SessionSummary
import com.mistbell.tavern.android.util.SessionExportFormat
import com.mistbell.tavern.android.util.SessionExportResult
import com.mistbell.tavern.android.util.SessionExporter
import com.mistbell.tavern.android.util.SessionImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class ChatListItem(
    val sessionId: String,
    val characterId: String,
    val sessionTitle: String,
    val characterName: String,
    val characterColor: String,
    val characterAvatarData: String? = null,
    val participantCharacters: List<Character> = emptyList(),
    val lastMessage: String,
    val lastMessageTime: String,
    val unreadCount: Int = 0,
    val isOnline: Boolean = false,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val lastMessageSender: String = "",
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class ChatListViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }

    private val db by lazy { TavernApplication.instance.container.database }
    private val ownerId = "local-user"

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedSessions = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    val selectedSessions: StateFlow<Set<Pair<String, String>>> = _selectedSessions.asStateFlow()

    val chatListItems: StateFlow<List<ChatListItem>> =
        flow {
            val database = db
            emitAll(
                combine(
                    database.sessionDao().getRecent("local-user"),
                    database.characterDao().getAll(),
                    _searchQuery.debounce(SEARCH_DEBOUNCE_MS),
                ) { sessions, characters, query ->
                    // combine 的 transform 跑在收集方（主线程）：批量查询 + JSON 解码 + 时间格式化都是重活，
                    // 整体切到 Default 线程，避免会话列表每次刷新都卡顿主线程
                    withContext(Dispatchers.Default) {
                        val characterMap = characters.associateBy { it.id }

                        // 批量获取所有会话的最后消息（修复 N+1 查询）
                        val lastMessages =
                            try {
                                database.messageDao().getLatestMessagesByOwner(ownerId)
                                    .associateBy { it.sessionId }
                            } catch (e: Exception) {
                                emptyMap()
                            }

                        val items =
                            sessions.mapNotNull { session ->
                                val character = characterMap[session.characterId] ?: return@mapNotNull null
                                val participantCharacters =
                                    session.participantCharacterIds()
                                        .mapNotNull { characterMap[it]?.toDomain() }
                                        .ifEmpty { listOf(character.toDomain()) }

                                // 从批量查询结果中获取最后消息
                                val lastMsg = lastMessages[session.id]
                                val lastMessage = lastMsg?.content?.take(50) ?: ""
                                val lastMessageRole = lastMsg?.role ?: ""

                                // Filter by search query
                                if (query.isNotBlank() &&
                                    !character.name.contains(query, ignoreCase = true) &&
                                    !lastMessage.contains(query, ignoreCase = true)
                                ) {
                                    return@mapNotNull null
                                }

                                // Determine sender label
                                val senderLabel =
                                    when (lastMessageRole) {
                                        "user" -> "我"
                                        "assistant" -> character.name
                                        else -> ""
                                    }

                                ChatListItem(
                                    sessionId = session.id,
                                    characterId = character.id,
                                    sessionTitle = session.title,
                                    characterName = character.name,
                                    characterColor = character.color,
                                    characterAvatarData = character.avatarData,
                                    participantCharacters = participantCharacters,
                                    lastMessage = lastMessage.ifBlank { session.title },
                                    lastMessageTime = formatTimestamp(session.updatedAt),
                                    unreadCount = session.unreadCount,
                                    isOnline = false,
                                    isPinned = session.isPinned,
                                    isMuted = session.isMuted,
                                    lastMessageSender = senderLabel,
                                )
                            }

                        // If no sessions, return sample data for preview
                        if (items.isEmpty() && query.isBlank()) {
                            emptyList()
                        } else {
                            items
                        }
                    }
                },
            )
        }.flowOn(Dispatchers.IO).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun navigateToChat(
        sessionId: String,
        characterId: String,
    ) {
        // Will be handled by navigation callback
    }

    private fun formatTimestamp(timestamp: String): String {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = dateFormat.parse(timestamp) ?: return "未知"

            val now = Calendar.getInstance()
            val messageTime = Calendar.getInstance().apply { time = date }

            val diffMillis = now.timeInMillis - messageTime.timeInMillis
            val diffMinutes = diffMillis / (60 * 1000)
            val diffHours = diffMillis / (60 * 60 * 1000)
            val diffDays = diffMillis / (24 * 60 * 60 * 1000)

            when {
                diffMinutes < 1 -> "刚刚"
                diffMinutes < 60 -> "${diffMinutes}分钟前"
                diffHours < 24 && now.get(Calendar.DAY_OF_YEAR) == messageTime.get(Calendar.DAY_OF_YEAR) -> {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
                }
                diffDays == 1L -> "昨天"
                diffDays < 7 -> {
                    val weekDays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
                    weekDays[messageTime.get(Calendar.DAY_OF_WEEK) - 1]
                }
                else -> SimpleDateFormat("M/d", Locale.getDefault()).format(date)
            }
        } catch (e: Exception) {
            "未知"
        }
    }

    fun togglePin(
        sessionId: String,
        characterId: String,
    ) {
        viewModelScope.launch {
            val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return@launch
            val newPinned = !session.isPinned
            val pinnedAt = if (newPinned) nowUtc() else null
            db.sessionDao().updatePinned(sessionId, ownerId, characterId, newPinned, pinnedAt)
        }
    }

    fun toggleMute(
        sessionId: String,
        characterId: String,
    ) {
        viewModelScope.launch {
            val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return@launch
            db.sessionDao().updateMuted(sessionId, ownerId, characterId, !session.isMuted)
        }
    }

    fun markAsRead(
        sessionId: String,
        characterId: String,
    ) {
        viewModelScope.launch {
            // 契约 1：markAsRead 会话级化（删除 character_id 过滤与参数），按会话+属主标记已读
            db.messageDao().markAsRead(sessionId, ownerId)
            db.sessionDao().updateUnreadCount(sessionId, ownerId, characterId, 0)
        }
    }

    fun renameSession(
        sessionId: String,
        characterId: String,
        title: String,
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return

        viewModelScope.launch {
            db.sessionDao().updateTitle(sessionId, ownerId, characterId, trimmedTitle, nowUtc())
        }
    }

    fun deleteSession(
        sessionId: String,
        characterId: String,
    ) {
        viewModelScope.launch {
            deleteSessionData(sessionId, characterId)
        }
    }

    private suspend fun deleteSessionData(
        sessionId: String,
        characterId: String,
    ) {
        // Room is the source of truth, but message vectors live outside Room and
        // must be removed for this session as well.
        TavernApplication.instance.container.vectorMemoryService.deleteSessionVectors(
            ownerId = ownerId,
            characterId = characterId,
            sessionId = sessionId,
        )
        db.memoryDao().deleteBySession(ownerId, characterId, sessionId)
        db.structuredMemoryDao().deleteBySession(ownerId, sessionId)
        db.vectorMemoryDao().deleteBySession(ownerId, sessionId)
        // 契约 1：deleteBySession 会话级化（删除 character_id 过滤与参数）
        db.messageDao().deleteBySession(sessionId, ownerId)
        db.sessionDao().delete(sessionId, ownerId, characterId)
    }

    fun deleteAllSessions() {
        viewModelScope.launch {
            db.sessionDao().getAllOnce()
                .filter { it.ownerId == ownerId }
                .forEach { session ->
                    TavernApplication.instance.container.vectorMemoryService.deleteSessionVectors(
                        ownerId = ownerId,
                        characterId = session.characterId,
                        sessionId = session.id,
                    )
                }
            db.memoryDao().deleteAll()
            db.structuredMemoryDao().deleteAll()
            db.vectorMemoryDao().deleteAll()
            db.messageDao().deleteAll()
            db.sessionDao().deleteAll()
        }
    }

    fun copySession(
        context: Context,
        sessionId: String,
        characterId: String,
    ) {
        viewModelScope.launch {
            try {
                val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return@launch
                val characterName = db.characterDao().getById(characterId)?.name ?: "AI"
                // 契约 1：getBySession 会话级化（删除 character_id 过滤与参数）
                val messages = db.messageDao().getBySession(sessionId, ownerId).first()
                val title = session.title.ifBlank { characterName }
                val content =
                    buildString {
                        appendLine(title)
                        appendLine()
                        messages.forEach { message ->
                            val speaker =
                                when (message.role) {
                                    "user" -> "我"
                                    "assistant" -> characterName
                                    else -> message.role.ifBlank { "消息" }
                                }
                            appendLine("$speaker: ${message.content}")
                            if (!message.thinking.isNullOrBlank()) {
                                appendLine("思考: ${message.thinking}")
                            }
                            appendLine()
                        }
                    }.trim()

                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText(title, content))
                Toast.makeText(context, "已复制会话", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportSession(
        context: Context,
        sessionId: String,
        characterId: String,
        format: SessionExportFormat,
        fileName: String,
        onComplete: (SessionExportResult?) -> Unit,
    ) {
        viewModelScope.launch {
            onComplete(exportSessionFile(context, sessionId, characterId, format, fileName))
        }
    }

    @Suppress("LongMethod")
    private suspend fun exportSessionFile(
        context: Context,
        sessionId: String,
        characterId: String,
        format: SessionExportFormat,
        fileName: String = SessionExporter.buildFileName("session", sessionId, format.extension),
    ): SessionExportResult? {
        return try {
            val session = db.sessionDao().get(sessionId, ownerId, characterId) ?: return null
            // 契约 1：getBySession 会话级化（删除 character_id 过滤与参数），导出取整个会话的消息
            val messages = db.messageDao().getBySession(sessionId, ownerId).first()
            val characterName = db.characterDao().getById(characterId)?.name ?: "AI"

            val sessionSummary =
                SessionSummary(
                    id = session.id,
                    title = session.title.ifBlank { characterName },
                    createdAt = session.createdAt,
                    updatedAt = session.updatedAt,
                    messageCount = messages.size,
                    characterId = session.characterId,
                    characterName = null,
                    // 契约 6 导出保真：SessionSummary 补会话模式（classic | group），
                    // @Serializable 序列化自动带上；旧导入方按默认值 classic 兼容
                    mode = session.mode,
                )

            val apiMessages =
                messages.map { msg ->
                    Message(
                        id = msg.id,
                        role = msg.role,
                        content = msg.content,
                        thinking = msg.thinking,
                        // 契约 6 导出保真：逐条补消息归属（群聊=说话 NPC id；classic=主角色），
                        // 导入侧据此还原 character_id，群聊会话导出后归属不丢失
                        characterId = msg.characterId,
                        createdAt = msg.createdAt,
                        memoryIds =
                            try {
                                kotlinx.serialization.json.Json.decodeFromString<List<String>>(msg.memoryIdsJson)
                            } catch (e: Exception) {
                                null
                            },
                        swipes =
                            try {
                                kotlinx.serialization.json.Json.decodeFromString<List<String>>(msg.swipesJson)
                            } catch (e: Exception) {
                                null
                            },
                        swipeIndex = msg.swipeIndex,
                        thinkingSwipes =
                            try {
                                kotlinx.serialization.json.Json.decodeFromString<List<String>>(msg.thinkingSwipesJson)
                            } catch (_: Exception) {
                                null
                            },
                    )
                }

            if (format == SessionExportFormat.JSONL) {
                SessionExporter.exportToJsonl(
                    context = context,
                    session = sessionSummary,
                    messages = apiMessages,
                    fileName = fileName,
                )
            } else {
                SessionExporter.exportToJson(
                    context = context,
                    session = sessionSummary,
                    messages = apiMessages,
                    fileName = fileName,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun enterMultiSelectMode() {
        _isMultiSelectMode.value = true
        _selectedSessions.value = emptySet()
    }

    fun exitMultiSelectMode() {
        _isMultiSelectMode.value = false
        _selectedSessions.value = emptySet()
    }

    fun toggleSessionSelection(
        sessionId: String,
        characterId: String,
    ) {
        val key = Pair(sessionId, characterId)
        _selectedSessions.value =
            if (_selectedSessions.value.contains(key)) {
                _selectedSessions.value - key
            } else {
                _selectedSessions.value + key
            }
    }

    fun selectAllSessions() {
        val allKeys = chatListItems.value.map { Pair(it.sessionId, it.characterId) }.toSet()
        _selectedSessions.value = allKeys
    }

    fun deleteSelectedSessions() {
        viewModelScope.launch {
            _selectedSessions.value.forEach { (sessionId, characterId) ->
                deleteSessionData(sessionId, characterId)
            }
            exitMultiSelectMode()
        }
    }

    fun exportSelectedSessions(
        context: android.content.Context,
        onComplete: (List<android.net.Uri>) -> Unit,
    ) {
        viewModelScope.launch {
            val uris = mutableListOf<android.net.Uri>()
            _selectedSessions.value.forEach { (sessionId, characterId) ->
                exportSessionFile(context, sessionId, characterId, SessionExportFormat.JSON)?.let { uris.add(it.uri) }
            }
            onComplete(uris)
            exitMultiSelectMode()
        }
    }

    private fun nowUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    // ========== 导入功能 ==========

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _importSuccess = MutableStateFlow<String?>(null)
    val importSuccess: StateFlow<String?> = _importSuccess.asStateFlow()

    /**
     * 从 JSON 文件导入会话
     * @param uri 文件 URI
     */
    @Suppress("CyclomaticComplexMethod")
    fun importSession(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 解析 JSON 文件
                val exportData = SessionImporter.importFromAny(getApplication(), uri)
                if (exportData == null) {
                    _importError.value = "无法解析导入文件，请检查文件格式"
                    return@launch
                }

                // 2. 检查角色是否存在
                val characterKey =
                    exportData.session.characterId?.takeIf { it.isNotBlank() }
                        ?: exportData.messages.firstOrNull { it.role == "assistant" }?.characterId.orEmpty()
                val character =
                    db.characterDao().getById(characterKey)
                        ?: db.characterDao().getAll().first().firstOrNull { it.name.equals(characterKey, ignoreCase = true) }
                if (character == null) {
                    _importError.value = "导入失败：找不到角色 ${characterKey.ifBlank { "ID 未提供" }}，请先导入对应角色卡"
                    return@launch
                }
                val knownCharacters = db.characterDao().getAll().first()

                // 3. 生成新的会话 ID（避免冲突）
                val newSessionId = "session-${System.currentTimeMillis()}-${(0..9999).random()}"
                val now = nowUtc()
                val characterId = character.id

                // 4. 创建会话实体
                val sessionEntity =
                    com.mistbell.tavern.android.data.local.entity.SessionEntity(
                        id = newSessionId,
                        ownerId = ownerId,
                        characterId = characterId,
                        title = exportData.session.title + " (导入)",
                        createdAt = now,
                        updatedAt = now,
                        // 使用默认提供商与模型
                        messageCount = exportData.messages.size,
                        providerId = "",
                        modelId = "",
                        worldBookId = "",
                        summaryJson = "",
                        unreadCount = 0,
                        isPinned = false,
                        pinnedAt = null,
                        isMuted = false,
                        enableLongTermMemory = null,
                        contextTokenLimit = null,
                        participantCharacterIdsJson = "",
                        themeId = "",
                        authorNote = "",
                        mode = exportData.session.mode,
                        modeConfigJson = "",
                    )

                // 5. 插入会话
                db.sessionDao().upsert(sessionEntity)

                // 6. 插入消息（生成新的消息 ID）
                val json = kotlinx.serialization.json.Json
                val stringListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())

                exportData.messages.forEach { message ->
                    val newMessageId = "msg-${System.currentTimeMillis()}-${(0..9999).random()}"
                    val messageCharacterId =
                        if (message.role == "assistant" && message.characterId.isNotBlank()) {
                            knownCharacters.firstOrNull {
                                it.id == message.characterId || it.name.equals(message.characterId, ignoreCase = true)
                            }?.id ?: characterId
                        } else {
                            characterId
                        }

                    // 序列化 swipes
                    val swipesJson =
                        if (message.swipes != null && message.swipes.isNotEmpty()) {
                            json.encodeToString(stringListSerializer, message.swipes)
                        } else {
                            ""
                        }

                    val thinkingSwipesJson =
                        if (message.thinkingSwipes != null && message.thinkingSwipes.isNotEmpty()) {
                            json.encodeToString(stringListSerializer, message.thinkingSwipes)
                        } else {
                            ""
                        }

                    // 序列化 memoryIds
                    val memoryIdsJson =
                        if (message.memoryIds != null && message.memoryIds.isNotEmpty()) {
                            json.encodeToString(stringListSerializer, message.memoryIds)
                        } else {
                            ""
                        }

                    val messageEntity =
                        com.mistbell.tavern.android.data.local.entity.MessageEntity(
                            id = newMessageId,
                            ownerId = ownerId,
                            // Preserve per-message speaker attribution for group chats;
                            // classic/legacy exports without it fall back to the session character.
                            characterId = messageCharacterId,
                            sessionId = newSessionId,
                            role = message.role,
                            content = message.content,
                            thinking = message.thinking,
                            createdAt = message.createdAt.ifBlank { now },
                            memoryIdsJson = memoryIdsJson,
                            swipesJson = swipesJson,
                            swipeIndex = message.swipeIndex,
                            thinkingSwipesJson = thinkingSwipesJson,
                            isRead = true,
                        )
                    db.messageDao().upsert(messageEntity)
                }

                // 7. 成功提示
                withContext(Dispatchers.Main) {
                    _importSuccess.value = "导入成功：${exportData.session.title}\n包含 ${exportData.messages.size} 条消息"
                }
            } catch (e: Exception) {
                _importError.value = "导入失败：${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun clearImportSuccess() {
        _importSuccess.value = null
    }
}
