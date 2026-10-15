package com.mistbell.tavern.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.LlmConfig
import com.mistbell.tavern.android.data.repository.BackupManager
import com.mistbell.tavern.android.data.repository.ChatSettingsResolver
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.ui.components.CONTEXT_TOKEN_MAX
import com.mistbell.tavern.android.ui.components.CONTEXT_TOKEN_MIN
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // stateIn 订阅超时：与全仓 ViewModel 的 WhileSubscribed(5000) 同值，收敛为具名常量
        private const val SUBSCRIBE_TIMEOUT_MS = 5000L
    }

    private val repo = SettingsRepository(application)
    private val db get() = TavernApplication.instance.container.database

    private val _llmConfig = MutableStateFlow(LlmConfig())
    val llmConfig: StateFlow<LlmConfig> = _llmConfig

    private val _settings = MutableStateFlow<JsonObject?>(null)
    val settings: StateFlow<JsonObject?> = _settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _darkMode = MutableStateFlow("system")
    val darkMode: StateFlow<String> = _darkMode

    private val _memoryExtractionPrompt = MutableStateFlow("")
    val memoryExtractionPrompt: StateFlow<String> = _memoryExtractionPrompt

    // --- S2 向量记忆召回设置（键解析实现收敛到 ChatSettingsResolver） ---
    // 源：auto（默认，有 key 走 API）/ api / local（本地 ONNX，实验性）
    val embeddingSource: StateFlow<String> =
        db.settingsDao()
            .observeValue("embedding_source")
            .map { it ?: "auto" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), "auto")

    val memoryRecallTopK: StateFlow<Int> =
        db.settingsDao()
            .observeValue("memory_recall_top_k")
            .map { ChatSettingsResolver.memoryRecallTopK(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
                ChatSettingsResolver.DEFAULT_RECALL_TOP_K,
            )

    val memorySimilarityThreshold: StateFlow<Float> =
        db.settingsDao()
            .observeValue("memory_similarity_threshold")
            .map { ChatSettingsResolver.memorySimilarityThreshold(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
                ChatSettingsResolver.DEFAULT_SIMILARITY_THRESHOLD,
            )

    // --- 提示词模板（全局 KV，明文存储；空值语义见各 resolver） ---
    //
    // 用 observeValue + stateIn 而不是一次性 getValue：备份恢复或别处改写后，编辑对话框能实时同步。
    // 这四项都**展示原始存储值**（未设置即空串），默认值在消费端（ChatSettingsResolver）与副标题里说明——
    // 若在这里回填默认值，用户一保存就会把默认文本固化成显式值，默认值日后调整将不再生效。

    val mainPromptSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_MAIN_PROMPT)

    val userNameSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_USER_NAME)

    val userPersonaSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_USER_PERSONA)

    val groupChatRulesSetting: StateFlow<String> = observeTextSetting(ChatSettingsResolver.KEY_GROUP_CHAT_RULES)

    private fun observeTextSetting(key: String): StateFlow<String> =
        db.settingsDao()
            .observeValue(key)
            .map { it.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), "")

    /**
     * 保存提示词类设置（四个键共用）。明文存储，自动纳入备份（BackupManager 整表导出 settings）。
     * 存空串 = 恢复"未设置"语义（主提示词/人设不注入、群聊规范回落内置默认）。
     */
    fun saveTextSetting(
        key: String,
        value: String,
        savedMessage: String,
    ) {
        viewModelScope.launch {
            db.settingsDao().upsert(com.mistbell.tavern.android.data.local.entity.SettingsEntity(key, value))
            _message.value = savedMessage
        }
    }

    // --- 对话生成设置（KV 缺省值：流式开 / 上下文 4096 / 长期记忆默认关） ---
    private val _streamingEnabled = MutableStateFlow(true)
    val streamingEnabled: StateFlow<Boolean> = _streamingEnabled

    private val _defaultContextTokens = MutableStateFlow(4096)
    val defaultContextTokens: StateFlow<Int> = _defaultContextTokens

    private val _defaultLtmEnabled = MutableStateFlow(false)
    val defaultLtmEnabled: StateFlow<Boolean> = _defaultLtmEnabled

    // --- 生成与采样设置（KV 键与 SettingsRepository.getLlmConfig 的组装约定一致） ---
    // 采样预设：creative/balanced/precise/custom（custom = 不套预设，去提供商页调参）
    private val _samplingPreset = MutableStateFlow("balanced")
    val samplingPreset: StateFlow<String> = _samplingPreset

    // 请求超时（秒，15..600）与重试次数（0..5）
    private val _requestTimeout = MutableStateFlow(90)
    val requestTimeout: StateFlow<Int> = _requestTimeout

    private val _requestRetries = MutableStateFlow(2)
    val requestRetries: StateFlow<Int> = _requestRetries

    init {
        loadSettings()
        loadLlmConfig()
        loadDarkMode()
        loadMemoryExtractionPrompt()
        observeGenerationSettings()
        observeSamplingSettings()
    }

    // 观察采样预设 / 超时 / 重试三个 KV 键（缺省：balanced / 90s / 2 次）
    private fun observeSamplingSettings() {
        viewModelScope.launch {
            db.settingsDao().observeValue("sampling_preset")
                .map { it ?: "balanced" }
                .collect { _samplingPreset.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("request_timeout_seconds")
                .map { (it?.toIntOrNull() ?: 90).coerceIn(15, 600) }
                .collect { _requestTimeout.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("request_retries")
                .map { (it?.toIntOrNull() ?: 2).coerceIn(0, 5) }
                .collect { _requestRetries.value = it }
        }
    }

    fun setSamplingPreset(name: String) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("sampling_preset", name),
            )
            _samplingPreset.value = name
        }
    }

    fun setRequestTimeout(seconds: Int) {
        val v = seconds.coerceIn(15, 600)
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("request_timeout_seconds", v.toString()),
            )
            _requestTimeout.value = v
        }
    }

    fun setRequestRetries(n: Int) {
        val v = n.coerceIn(0, 5)
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("request_retries", v.toString()),
            )
            _requestRetries.value = v
        }
    }

    // 从 settings 表观察三个对话生成相关 KV 键并解析灌入 StateFlow
    private fun observeGenerationSettings() {
        viewModelScope.launch {
            db.settingsDao().observeValue("streaming_enabled")
                .map { it != "0" } // 缺省/null 均视为开启
                .collect { _streamingEnabled.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("default_context_tokens")
                .map { (it?.toIntOrNull() ?: 4096).coerceIn(CONTEXT_TOKEN_MIN, CONTEXT_TOKEN_MAX) }
                .collect { _defaultContextTokens.value = it }
        }
        viewModelScope.launch {
            db.settingsDao().observeValue("default_ltm_enabled")
                .map { it == "1" } // 缺省/null 视为关闭
                .collect { _defaultLtmEnabled.value = it }
        }
    }

    fun setStreamingEnabled(v: Boolean) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("streaming_enabled", if (v) "1" else "0"),
            )
            _streamingEnabled.value = v
        }
    }

    fun setDefaultContextTokens(n: Int) {
        val v = n.coerceIn(CONTEXT_TOKEN_MIN, CONTEXT_TOKEN_MAX)
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("default_context_tokens", v.toString()),
            )
            _defaultContextTokens.value = v
        }
    }

    fun setDefaultLtmEnabled(v: Boolean) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("default_ltm_enabled", if (v) "1" else "0"),
            )
            _defaultLtmEnabled.value = v
        }
    }

    private fun loadDarkMode() {
        viewModelScope.launch {
            val mode = db.settingsDao().getValue("dark_mode") ?: "system"
            _darkMode.value = mode
        }
    }

    fun setDarkMode(mode: String) {
        viewModelScope.launch {
            db.settingsDao().upsert(com.mistbell.tavern.android.data.local.entity.SettingsEntity("dark_mode", mode))
            _darkMode.value = mode
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repo.loadAndCacheSettings()
                _settings.value = repo.observeSettings().first() as? JsonObject
            } catch (e: Exception) {
                _message.value = "加载设置失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadLlmConfig() {
        viewModelScope.launch {
            _llmConfig.value = repo.getLlmConfig()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    // --- S2 向量记忆设置写入口 ---

    fun setEmbeddingSource(source: String) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity("embedding_source", source),
            )
            runCatching { TavernApplication.instance.container.refreshEmbeddingServices() }
                .onFailure { error ->
                    _message.value = "Embedding 源已保存，但刷新服务失败：${error.message}"
                }
        }
    }

    fun setMemoryRecallTopK(value: Int) {
        val normalized =
            value.coerceIn(
                ChatSettingsResolver.RECALL_TOP_K_MIN,
                ChatSettingsResolver.RECALL_TOP_K_MAX,
            )
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(
                    "memory_recall_top_k",
                    normalized.toString(),
                ),
            )
        }
    }

    fun setMemorySimilarityThreshold(value: Float) {
        val normalized =
            value.coerceIn(
                ChatSettingsResolver.SIMILARITY_THRESHOLD_MIN,
                ChatSettingsResolver.SIMILARITY_THRESHOLD_MAX,
            )
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(
                    "memory_similarity_threshold",
                    normalized.toString(),
                ),
            )
        }
    }

    // --- 数据（S4 全量备份/恢复） ---
    fun createBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val summary = BackupManager(getApplication()).exportToUri(uri)
                _message.value = "备份完成：已导出 ${summary.totalAdded} 项数据"
            } catch (e: Exception) {
                android.util.Log.e("Settings", "Backup export failed", e)
                _message.value = e.message ?: "备份失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun restoreBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val summary = BackupManager(getApplication()).restoreFromUri(uri)
                _message.value =
                    "恢复完成：合并 ${summary.totalAdded} 项" +
                    (if (summary.totalSkipped > 0) "（跳过已存在 ${summary.totalSkipped} 项）" else "")
            } catch (e: Exception) {
                android.util.Log.e("Settings", "Backup restore failed", e)
                _message.value = e.message ?: "恢复失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadMemoryExtractionPrompt() {
        viewModelScope.launch {
            val prompt =
                db.settingsDao().getValue("memory_extraction_prompt")
                    ?: getDefaultMemoryExtractionPrompt()
            _memoryExtractionPrompt.value = prompt
        }
    }

    fun saveMemoryExtractionPrompt(prompt: String) {
        viewModelScope.launch {
            db.settingsDao().upsert(
                com.mistbell.tavern.android.data.local.entity.SettingsEntity(
                    "memory_extraction_prompt",
                    prompt,
                ),
            )
            _memoryExtractionPrompt.value = prompt
            _message.value = "记忆提取提示词已保存"
        }
    }

    fun getDefaultMemoryExtractionPrompt(): String {
        return """
            你是角色扮演聊天系统的长期记忆抽取引擎。请分析这一轮对话中是否存在值得长期保存的记忆。

            只输出一个 JSON 对象，结构必须完全如下：
            {
              "emotion": {
                "primaryEmotion": "简短情绪标签或空字符串",
                "secondaryEmotion": "简短情绪标签或空字符串",
                "intensity": 0.0,
                "situationType": "normal",
                "memoryWorthiness": 0.0,
                "stabilityMultiplier": 1.0,
                "emotionalAtmosphere": "简短氛围或空字符串",
                "reasoning": "简短理由"
              },
              "triplets": [
                {
                  "subject": "规范主体，用户事实通常使用 user",
                  "relation": "关系或动词",
                  "object": "目标或取值",
                  "memoryType": "fact|event|emotion|core|preference|identity|relationship|goal|note",
                  "importance": 0.0,
                  "tags": ["3-8 个用于检索的稳定关键词"],
                  "aliases": ["0-6 个用户可能用来提起这条记忆的说法"],
                  "rawText": "一条基于原文的正式记忆句"
                }
              ],
              "npcMentions": []
            }

            situationType must be one of:
            life_death, deep_trauma, sacred_moment, vulnerability, betrayal,
            reunion, parting, identity_reveal, normal.

            只抽取持久信息：身份、稳定偏好、关系、重要事件、创伤/恐惧、承诺、目标、边界、反复关注的问题。
            可以从 User 和 Assistant 两侧抽取：User 表达的偏好/边界/身份，以及 Assistant 回复中已经发生或确认的剧情事件。
            不要保存寒暄、填充语、临时情绪，或原文没有表达的事实。
            core 记忆只用于生死、誓言、关键身份揭示、神圣转折点。
            如果没有值得保存的内容，triplets 返回 []。

            语言规则非常重要：
            - rawText、object、tags、aliases 必须优先使用用户原文语言。
            - 用户用中文表达时，rawText 必须是中文正式记忆句，不要翻译成英文。
            - 可以保留主体 user，但其余内容尽量中文化。
            - 不要输出 "user refuse to wear women's clothing" 这类英文句；应输出 "user 拒绝穿女装"。

            rawText 必须是正式记忆句，不是一句第一人称原话。
            用户事实优先使用 subject "user"。例如：
            - "我叫墨轩" -> subject "user", relation "name", object "墨轩", rawText "user 的名字是墨轩"
            - "我喜欢结构化长期记忆" -> rawText "user 喜欢结构化长期记忆"
            - "我可不穿女人衣服，给我个斗笠" -> rawText "user 拒绝穿女装" / "user 想要斗笠"

            ⚠️ 重要：区分"陈述事实"和"告知信息"
            当 NPC 告诉 user 某个信息时，记录"NPC 知道/告知"，而非直接断言：
            - ❌ 错误："角色：你不会游泳" -> rawText "user 不会游泳"（这是直接断言）
            - ✅ 正确："角色：你不会游泳" -> subject "角色名", relation "知道", object "user不会游泳", rawText "角色名知道user不会游泳"
            - ❌ 错误："医生：你的血压偏高" -> rawText "user 血压偏高"
            - ✅ 正确："医生：你的血压偏高" -> rawText "医生告知user血压偏高"

            只有当 user 自己陈述或事实已确认时，才直接记录为 user 的属性：
            - ✅ 正确："User: 我确实不会游泳" -> rawText "user 不会游泳"
            - ✅ 正确："角色检查后确认user对花粉过敏" -> rawText "user 对花粉过敏（角色确认）"

            当 user 向 NPC 告知自己的信息时，可以同时记录事实和知情关系：
            - "User: 我不会游泳" -> 可提取两条：rawText "user 不会游泳" + rawText "角色名知道user不会游泳"

            ❌ 错误示例（不要这样做）：
            - rawText: "User: 想带我走就带嘛 Assistant: 角色听你这么说..." （这是原对话，不是记忆句）
            - rawText: "下午-市中心>咖啡店>靠窗位置..." （这是场景描述，不是记忆）
            - rawText: "她笑够了，站起身来，拍了拍衣服..." （这是过程描写，不是记忆）

            ✅ 正确示例：
            - rawText: "user 表达了愿意跟随角色"
            - rawText: "角色指出 user 需要帮助"
            - rawText: "user 和角色在公园中对话"

            重要约束：
            - rawText 长度必须在 10-100 字之间
            - 不要包含 "User:" "Assistant:" 等对话标记
            - 不要包含场景描述标签（如 [下午-地点>场所...]）
            - 不要包含大段角色动作或心理描写
            - 只提取核心事实、关系、偏好、事件

            对话片段：
            %s

            不要输出 Markdown，不要解释，只输出 JSON。
            """.trimIndent()
    }

    // --- Changelog ---

    private val _changelog = MutableStateFlow<List<com.mistbell.tavern.android.data.model.VersionInfo>>(emptyList())
    val changelog: StateFlow<List<com.mistbell.tavern.android.data.model.VersionInfo>> = _changelog.asStateFlow()

    private val _isLoadingChangelog = MutableStateFlow(false)
    val isLoadingChangelog: StateFlow<Boolean> = _isLoadingChangelog.asStateFlow()

    fun loadChangelog() {
        viewModelScope.launch {
            _isLoadingChangelog.value = true
            try {
                val api = ApiClient.getApi(getApplication())
                val response = api.getChangelog()
                _changelog.value = response.versions
            } catch (e: Exception) {
                // 降级到默认数据
                _changelog.value = getDefaultChangelog()
            } finally {
                _isLoadingChangelog.value = false
            }
        }
    }

    private fun getDefaultChangelog(): List<com.mistbell.tavern.android.data.model.VersionInfo> {
        return listOf(
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.3-beta",
                versionCode = 14,
                releaseDate = "2026-10-03",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "补齐 continue、swipe 与正常发送、重生、继续、取消、部分回复的统一落库链路，保持正文、思维链和候选索引一致",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "增强 World Info：多来源合并、次关键词、三轮递归扫描、sticky/cooldown/delay、概率和分组择优",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "PromptTrace 增加激活原因、插入位置、历史裁剪原因和 OpenAI/Anthropic/Gemini 协议映射说明",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "删除、撤销、回退、重生和清空会话时同步清理 VectorStore，避免 Room 消息与向量记录脱节",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "保持 Room 为主存储，新增酒馆 JSONL 双向导入导出并保留消息顺序、角色归属、thinking、swipes 和 thinkingSwipes",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.1-beta",
                versionCode = 12,
                releaseDate = "2026-10-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "统一聊天、会话、角色、世界书和设置页面的视觉风格，并支持横屏/平板 NavigationRail 自适应导航",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "角色卡与会话卡支持点击、长按和三点菜单，聊天页支持完整提示词查看与开场白切换",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "API 配置卡片化，SSE 流式开关按 API 保存，高级采样、超时和重试设置集中管理",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "聊天底部输入栏与渐隐遮罩改为统一测量和绘制，减少键盘切换时的透明缝隙与布局跳动",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "背景图加载、消息列表渲染和点击收起键盘逻辑整理，降低无效布局与重组",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "修复角色背景图异步加载期间底色不稳定、底部渐隐遮罩被覆盖的问题",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "更新 Android 17（API 37）编译与目标 SDK，保留 Android 8.0（API 26）最低支持",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.9.0-beta",
                versionCode = 11,
                releaseDate = "2026-09-26",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "本地语义向量：内置 bge-small-zh 模型（ONNX），无需 API Key 即可使用真正的语义记忆召回",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "向量记忆设置：记忆源三档（自动/API/本地 ONNX）、召回条数与相似度阈值可调",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "安装包按 CPU 架构分包（arm64 约 53MB），不再被无关架构拖大",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.8.0-beta",
                versionCode = 10,
                releaseDate = "2026-09-26",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "全量备份/恢复：角色、会话、记忆、设置与主题包打包为单个 zip；合并恢复不删本地数据",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "首启引导：新用户三步上手（内置示例角色一键导入 + API Key 图文指引），全部步骤可跳过",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "会话设置三态化：上下文长度/长期记忆支持「跟随全局」，全局默认改动对存量会话真正生效",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "LLM 配置双源真相根修：采样预设/超时/重试设置此前对实际聊天请求不生效",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.7.0-beta",
                versionCode = 9,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "群聊模式：多角色会话（上限 4），AI 轮流以各角色身份发言，气泡按说话者显示名字与配色",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "@名字 指定谁回应；“让TA继续”一键推动下一位角色发言",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "会话模式骨架（v17 迁移）：老会话自动经典模式，无感升级",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "经典模式角色点选收敛修复；群聊消息读写改为会话级（NPC 发言不再丢失）",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.6.0-beta",
                versionCode = 8,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "长会话窗口分页：打开只加载最近 200 条，上滚自动加载更早消息并保持阅读位置",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "冷启动与会话列表提速（新数据库索引 + 重活移出主线程 + 头像位图缓存降采样）",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "流式输出 80ms 节流且只刷新流式气泡，长回复生成不再整机发涩",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "滚动体验重做：进会话直达底部，上翻阅读不再被强制拉回",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "撤销/回退/重新生成不再丢失已加载的更早历史；数据库 v16 迁移（分页性能索引）",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.5.0-beta",
                versionCode = 7,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "真流式输出：逐 token 渲染，生成中可停止（已生成部分保留）",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "采样预设（创意/平衡/精确）与高级参数、超时重试、会话附加指令",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "长期记忆词法召回：CJK 分词 + 会话内检索，替代伪向量（ONNX 语义向量后置）",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "improvement",
                            "设置页按新信息架构重组；流式开关/默认上下文长度/默认长期记忆可配",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "fix",
                            "消息回溯/重新生成/失败回滚等十余项正确性修复；API Key 加密存储",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.4.0-beta",
                versionCode = 6,
                releaseDate = "2026-09-02",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "主题包系统（T1）：zip 导入/分享，角色专属/全局主题，聊天页配色与背景",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "会话级主题：会话 → 角色 → 全局 → 默认层层回落",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "SillyTavern 生态互通：角色卡 v1/v2 JSON 与 PNG 埋卡导入导出、世界书导入",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "宏引擎：{{char}}/{{user}}/{{random}}/{{roll::3d6}} 等，未知宏原样保留",
                        ),
                        com.mistbell.tavern.android.data.model.ChangeItem(
                            "feature",
                            "think 标签提取为折叠思考区；导入诊断报告",
                        ),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.3.0",
                versionCode = 5,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "应用冷启动优化（完全延迟初始化）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "启用资源压缩和代码优化"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "添加 ProGuard 规则支持混淆"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "集成 Paging 3 库（消息分页基础）"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.2.2",
                versionCode = 4,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "优化 Compose 重组性能（时间戳缓存）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "向量存储延迟加载优化"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "向量存储内存限制（最多 1000 条）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "优化列表项比较逻辑减少重组"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.2.1",
                versionCode = 3,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "数据库查询性能优化（添加关键索引）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "修复会话列表 N+1 查询问题"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "向量搜索结果缓存优化"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "LLM API 添加超时重试机制"),
                        com.mistbell.tavern.android.data.model.ChangeItem("fix", "修复重装应用后数据库迁移失败问题"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.2.0",
                versionCode = 2,
                releaseDate = "2026-06-22",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "引号高亮显示（支持中英日文引号）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "动作括号高亮（橙色斜体）"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "全局点击外部收起键盘"),
                        com.mistbell.tavern.android.data.model.ChangeItem("improvement", "角色编辑页面完全中文化"),
                    ),
            ),
            com.mistbell.tavern.android.data.model.VersionInfo(
                version = "0.1.0",
                versionCode = 1,
                releaseDate = "2026-06-20",
                changes =
                    listOf(
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "初始版本发布"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "角色管理功能"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "聊天对话功能"),
                        com.mistbell.tavern.android.data.model.ChangeItem("feature", "LLM 提供商配置"),
                    ),
            ),
        )
    }
}
