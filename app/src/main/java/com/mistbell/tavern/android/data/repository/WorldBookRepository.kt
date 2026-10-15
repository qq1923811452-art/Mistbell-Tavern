package com.mistbell.tavern.android.data.repository

import android.content.Context
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.api.ApiClient
import com.mistbell.tavern.android.data.api.model.WorldBook
import com.mistbell.tavern.android.data.api.model.WorldBookEntry
import com.mistbell.tavern.android.data.local.entity.WorldBookEntity
import com.mistbell.tavern.android.data.local.entity.WorldBookEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class WorldBookRepository(private val context: Context) {
    private val db get() = TavernApplication.instance.container.database
    private val api get() = ApiClient.getApi(context)

    fun observeWorldBooks(): Flow<List<WorldBook>> {
        return db.worldBookDao().getAll().map { bookEntities ->
            bookEntities.map { book ->
                val entries = db.worldBookDao().getEntriesList(book.id)
                book.toDomain(entries)
            }
        }
    }

    fun observeEntries(bookId: String): Flow<List<WorldBookEntry>> {
        return db.worldBookDao().getEntries(bookId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun loadFromServer() {
        // 完全本地化：无服务器依赖，此方法保留为空以兼容现有调用点（刷新按钮等）
    }

    suspend fun createWorldBook(name: String): WorldBook? {
        return withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val entity = WorldBookEntity(id = id, name = name, settingsJson = "{}")
            db.worldBookDao().upsertBook(entity)
            WorldBook(id = id, name = name, entries = emptyList())
        }
    }

    suspend fun deleteWorldBook(id: String) {
        withContext(Dispatchers.IO) {
            db.worldBookDao().deleteEntriesByBookId(id)
            db.worldBookDao().deleteBookById(id)
        }
    }

    suspend fun createEntry(
        bookId: String,
        comment: String,
        keys: List<String>,
        content: String,
        constant: Boolean = false,
        disable: Boolean = false,
        insertPosition: String = "before_prompt",
        depth: Int = 0,
        probability: Double = 1.0,
        depthRole: String = "system",
    ): WorldBookEntry? {
        return withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val keysJson =
                Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                    keys,
                )
            val entity =
                WorldBookEntryEntity(
                    id = id,
                    bookId = bookId,
                    comment = comment,
                    keysJson = keysJson,
                    content = content,
                    constant = constant,
                    disable = disable,
                    insertPosition = insertPosition,
                    depth = depth,
                    probability = probability,
                    depthRole = depthRole,
                    order = 100,
                )
            db.worldBookDao().upsertEntries(listOf(entity))
            WorldBookEntry(
                id = id,
                comment = comment,
                key = keys,
                content = content,
                constant = constant,
                disable = disable,
                insertPosition = insertPosition,
                depth = depth,
                probability = probability,
                depthRole = depthRole,
                order = 100,
            )
        }
    }

    suspend fun updateEntry(
        entryId: String,
        patch: JsonObject,
    ) {
        withContext(Dispatchers.IO) {
            db.worldBookDao().getEntryById(entryId)?.let { existing ->
                db.worldBookDao().upsertEntries(listOf(existing.applyPatch(patch)))
            }
        }
    }

    suspend fun deleteEntry(entryId: String) {
        withContext(Dispatchers.IO) {
            db.worldBookDao().deleteEntryById(entryId)
        }
    }

    suspend fun exportWorldBook(): JsonElement? {
        return withContext(Dispatchers.IO) {
            try {
                api.exportWorldBook()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 将 JSON patch 应用到实体（本地优先写入时用于更新已有条目）。
     * 只覆盖 patch 中实际提供的字段；insertPosition/depth 自 v19 起已落库，随 patch 更新。
     */
    private fun WorldBookEntryEntity.applyPatch(patch: JsonObject): WorldBookEntryEntity {
        val keysJson =
            (patch["key"] as? JsonArray)?.let { arr ->
                Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                    arr.map { it.jsonPrimitive.content },
                )
            } ?: this.keysJson
        return copy(
            comment = patch["comment"]?.jsonPrimitive?.content ?: comment,
            content = patch["content"]?.jsonPrimitive?.content ?: content,
            constant = patch["constant"]?.jsonPrimitive?.booleanOrNull ?: constant,
            disable = patch["disable"]?.jsonPrimitive?.booleanOrNull ?: disable,
            order = patch["order"]?.jsonPrimitive?.intOrNull ?: order,
            insertPosition = patch["insertPosition"]?.jsonPrimitive?.content ?: insertPosition,
            depth = patch["depth"]?.jsonPrimitive?.intOrNull ?: depth,
            probability = patch["probability"]?.jsonPrimitive?.doubleOrNull ?: probability,
            depthRole = patch["depthRole"]?.jsonPrimitive?.content ?: depthRole,
            keysJson = keysJson,
        )
    }
}
