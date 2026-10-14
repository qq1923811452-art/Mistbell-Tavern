package com.mistbell.tavern.android.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自定义提示词实体
 */
@Entity(
    tableName = "custom_prompts",
    indices = [
        Index(value = ["type"], name = "index_custom_prompts_type"),
        Index(value = ["position"], name = "index_custom_prompts_position"),
        Index(value = ["isEnabled"], name = "index_custom_prompts_isEnabled"),
        Index(value = ["priority"], name = "index_custom_prompts_priority"),
    ],
)
data class CustomPromptEntity(
    @PrimaryKey val id: String,
    // JAILBREAK, WRITING_STYLE, CHARACTER, CUSTOM
    val name: String,
    val content: String,
    val type: String,
    // SYSTEM_START, SYSTEM_END, USER_PREFIX, USER_SUFFIX, ASSISTANT_PREFIX
    val position: String,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    // JSON 数组字符串
    val tags: String = "",
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
