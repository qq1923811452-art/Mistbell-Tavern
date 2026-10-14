package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * API 配置实体
 */
@Entity(tableName = "api_configs")
data class ApiConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    // UNKNOWN, TESTING, SUCCESS, FAILED
    val isDefault: Boolean = false,
    val lastTestStatus: String = "UNKNOWN",
    val lastTestTime: Long = 0L,
    // 用于自定义排序
    val sortOrder: Int = 0,
    // 接口类型：openai / anthropic / gemini / custom（custom = 任意 OpenAI 兼容网关）
    @ColumnInfo(defaultValue = "'openai'")
    val type: String = "openai",
    // 1M 上下文开关（解锁 1M 档位）
    @ColumnInfo(defaultValue = "0")
    val context1m: Boolean = false,
    // SSE 流式传输开关：按 API 配置独立控制，关闭后使用整包响应
    @ColumnInfo(defaultValue = "1")
    val streamingEnabled: Boolean = true,
    // 思考模式开关：true = 禁用思考（请求体发 thinking:{"type":"disabled"}）。
    // 迁移默认 0（保持老配置原行为不变）；新建配置时 UI 默认置 ON。
    @ColumnInfo(defaultValue = "0")
    val thinkingDisabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
