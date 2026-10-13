package com.mistbell.tavern.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mistbell.tavern.android.data.api.model.Memory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "memories")
@Serializable
data class MemoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "session_id") val sessionId: String = "",
    val layer: String,
    val type: String,
    val subject: String,
    val relation: String,
    @ColumnInfo(name = "object")
    @SerialName("object")
    val objectValue: String,
    val content: String,
    val importance: Double,
    val stability: Double,
    val status: String,
    @ColumnInfo(name = "access_count") val accessCount: Int,
    val tags: String,
    val aliases: String,
) {
    fun toDomain(): Memory {
        val tagList =
            try {
                if (tags.isNotBlank()) {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(tags)
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

        val aliasList =
            try {
                if (aliases.isNotBlank()) {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(aliases)
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

        return Memory(
            id = id,
            content = content,
            type = type,
            layer = layer,
            subject = subject,
            relation = relation,
            `object` = objectValue,
            importance = importance,
            stability = stability,
            status = status,
            accessCount = accessCount,
            tags = tagList,
            aliases = aliasList,
        )
    }

    companion object {
        fun fromDomain(
            m: Memory,
            ownerId: String,
            characterId: String,
            sessionId: String = "",
        ): MemoryEntity {
            val json = kotlinx.serialization.json.Json
            val stringListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
            val tagsStr = json.encodeToString(stringListSerializer, m.tags)
            val aliasesStr = json.encodeToString(stringListSerializer, m.aliases)
            return MemoryEntity(
                id = m.id,
                ownerId = ownerId,
                characterId = characterId,
                sessionId = sessionId,
                layer = m.layer,
                type = m.type,
                subject = m.subject,
                relation = m.relation,
                objectValue = m.`object`,
                content = m.content,
                importance = m.importance,
                stability = m.stability,
                status = m.status,
                accessCount = m.accessCount,
                tags = tagsStr,
                aliases = aliasesStr,
            )
        }
    }
}
