package com.mistbell.tavern.android.service

import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import com.mistbell.tavern.android.service.models.ActivatedEntry
import kotlin.random.Random

/**
 * 本地世界书服务
 *
 * 移植自后端 WorldBookService.java
 * 负责根据关键词激活世界书条目
 */
class LocalWorldBookService {
    companion object {
        private const val DEFAULT_PROBABILITY = 1.0
        private const val DEFAULT_MAX_BUDGET = 8000
    }

    /**
     * 激活世界书条目
     *
     * @param entries 所有世界书条目
     * @param scanText 要扫描的文本（通常是用户消息 + 最近的对话）
     * @param maxBudget 最大字符预算
     * @return 激活的条目列表
     */
    fun activateEntries(
        entries: List<WorldBookEntryEntity>,
        scanText: String,
        maxBudget: Int = DEFAULT_MAX_BUDGET,
        recentTexts: List<String> = emptyList(),
        maxDepth: Int = 3,
    ): List<ActivatedEntry> {
        val activated = mutableListOf<ActivatedEntry>()
        var usedBudget = 0

        // 按优先级排序（order 越小优先级越高）
        val sortedEntries = entries.sortedBy { it.order }

        for (entry in sortedEntries) {
            // 检查是否启用（disable = false 表示启用）
            if (entry.disable) continue

            // 检查是否是常量条目（constant = true 总是激活）
            val isConstant = entry.constant

            // 解析关键词
            val keys =
                try {
                    if (entry.keysJson.isNotBlank()) {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(entry.keysJson)
                    } else {
                        emptyList()
                    }
                } catch (_: Exception) {
                    emptyList()
                }

            // 检查关键词匹配
            val scanCorpus = buildScanCorpus(scanText, recentTexts, maxDepth)
            val keyMatched = isConstant || matchKeywords(keys, scanCorpus)

            if (!keyMatched) continue
            if (!isConstant && !checkProbability(entry.probability)) continue

            // 检查预算
            val entrySize = entry.content.length
            if (usedBudget + entrySize > maxBudget) {
                // 预算不足，跳过
                continue
            }

            // 激活该条目（使用默认值填充缺失字段）；position 默认为 before。
            activated.add(
                ActivatedEntry(
                    id = entry.id,
                    comment = entry.comment,
                    content = entry.content,
                    keys = keys,
                    position = "before",
                    depth = null,
                    order = entry.order,
                    probability = entry.probability,
                    enabled = true,
                ),
            )

            usedBudget += entrySize
        }

        return activated
    }

    /** 合并当前消息、历史和条目次关键词，供递归/深度扫描使用。 */
    fun buildScanCorpus(current: String, recentTexts: List<String>, maxDepth: Int): String =
        buildString {
            appendLine(current)
            recentTexts.takeLast(maxDepth.coerceAtLeast(0)).forEach(::appendLine)
        }

    /**
     * 关键词匹配
     *
     * @param keys 关键词列表
     * @param text 要搜索的文本
     * @return 是否有任一关键词匹配
     */
    fun matchKeywords(
        keys: List<String>,
        text: String,
    ): Boolean {
        if (keys.isEmpty()) return false

        val lowerText = text.lowercase()

        return keys.any { key ->
            val lowerKey = key.lowercase()

            // 支持正则表达式
            if (key.startsWith("/") && key.endsWith("/")) {
                val pattern = key.substring(1, key.length - 1)
                try {
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(text)
                } catch (e: Exception) {
                    // 正则表达式无效，回退到普通匹配
                    lowerText.contains(lowerKey)
                }
            } else {
                // 普通字符串匹配（支持通配符 *）
                if (lowerKey.contains("*")) {
                    val pattern =
                        lowerKey
                            .replace("*", ".*")
                            .let { ".*$it.*" }
                    try {
                        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                        regex.matches(lowerText)
                    } catch (e: Exception) {
                        lowerText.contains(lowerKey.replace("*", ""))
                    }
                } else {
                    lowerText.contains(lowerKey)
                }
            }
        }
    }

    /**
     * 检查概率
     *
     * @param probability 概率值 (0.0 - 1.0)
     * @return 是否通过概率检查
     */
    private fun checkProbability(probability: Double): Boolean {
        if (probability >= 1.0) return true
        if (probability <= 0.0) return false

        val random = Random.nextDouble()
        return random < probability
    }

    /**
     * 按位置分组
     *
     * @param entries 激活的条目
     * @return 按位置分组的条目 Map
     */
    fun groupByPosition(entries: List<ActivatedEntry>): Map<String, List<ActivatedEntry>> {
        return entries.groupBy { it.position }
    }

    /**
     * 按深度分组
     *
     * @param entries 激活的条目
     * @return 按深度分组的条目 Map（只包含有深度的条目）
     */
    fun groupByDepth(entries: List<ActivatedEntry>): Map<Int, List<ActivatedEntry>> {
        return entries
            .filter { it.depth != null && it.depth > 0 }
            .groupBy { it.depth!! }
    }

    /**
     * 计算激活条目的总字符数
     */
    fun calculateTotalChars(entries: List<ActivatedEntry>): Int {
        return entries.sumOf { it.content.length }
    }

    /**
     * 构建扫描文本
     *
     * 通常包含用户当前消息 + 最近几条对话历史
     *
     * @param userMessage 用户当前消息
     * @param recentMessages 最近的消息历史
     * @param maxMessages 最多包含多少条历史消息
     * @return 组合的扫描文本
     */
    fun buildScanText(
        userMessage: String,
        recentMessages: List<String> = emptyList(),
        maxMessages: Int = 5,
    ): String {
        return buildString {
            // 用户当前消息
            appendLine(userMessage)
            appendLine()

            // 最近的历史消息
            recentMessages.takeLast(maxMessages).forEach { msg ->
                appendLine(msg)
            }
        }
    }
}
