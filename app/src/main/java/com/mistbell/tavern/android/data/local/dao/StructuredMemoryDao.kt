package com.mistbell.tavern.android.data.local.dao

import androidx.room.*
import com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StructuredMemoryDao {
    @Query("SELECT * FROM structured_memory WHERE owner_id = :ownerId ORDER BY created_at DESC")
    fun getAll(ownerId: String): Flow<List<StructuredMemoryEntity>>

    @Query("SELECT * FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId ORDER BY created_at DESC")
    fun getByCharacter(
        ownerId: String,
        characterId: String,
    ): Flow<List<StructuredMemoryEntity>>

    /** 提示词召回只需有限候选，避免每轮把角色全部记忆加载到 Kotlin。 */
    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId " +
            "AND (structured_data IS NULL OR " +
            "CASE WHEN json_valid(structured_data) THEN COALESCE(json_extract(structured_data, '$.status'), '') != 'superseded' ELSE 1 END) " +
            "ORDER BY importance DESC, created_at DESC LIMIT :limit",
    )
    suspend fun getPromptCandidates(
        ownerId: String,
        characterId: String,
        limit: Int,
    ): List<StructuredMemoryEntity>

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId " +
            "AND session_id = :sessionId ORDER BY created_at DESC",
    )
    fun getBySession(
        ownerId: String,
        characterId: String,
        sessionId: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Query("SELECT * FROM structured_memory WHERE owner_id = :ownerId AND session_id = :sessionId ORDER BY created_at DESC")
    fun getBySession(
        ownerId: String,
        sessionId: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Query("SELECT * FROM structured_memory WHERE id = :id")
    suspend fun getById(id: Long): StructuredMemoryEntity?

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND memory_type = :memoryType " +
            "ORDER BY importance DESC, created_at DESC",
    )
    fun getByType(
        ownerId: String,
        memoryType: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND session_id = :sessionId " +
            "AND memory_type = :memoryType ORDER BY importance DESC, created_at DESC",
    )
    fun getBySessionAndType(
        ownerId: String,
        sessionId: String,
        memoryType: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND importance >= :minImportance " +
            "ORDER BY importance DESC, created_at DESC",
    )
    fun getByImportance(
        ownerId: String,
        minImportance: Int,
    ): Flow<List<StructuredMemoryEntity>>

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND " +
            "(title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY importance DESC, created_at DESC",
    )
    fun search(
        ownerId: String,
        query: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Query(
        "SELECT * FROM structured_memory WHERE owner_id = :ownerId AND session_id = :sessionId AND " +
            "(title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY importance DESC, created_at DESC",
    )
    fun searchBySession(
        ownerId: String,
        sessionId: String,
        query: String,
    ): Flow<List<StructuredMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: StructuredMemoryEntity): Long

    @Update
    suspend fun update(memory: StructuredMemoryEntity)

    @Delete
    suspend fun delete(memory: StructuredMemoryEntity)

    @Query("DELETE FROM structured_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId")
    suspend fun deleteByCharacter(
        ownerId: String,
        characterId: String,
    )

    @Query("DELETE FROM structured_memory WHERE owner_id = :ownerId AND session_id = :sessionId")
    suspend fun deleteBySession(
        ownerId: String,
        sessionId: String,
    )

    @Query("DELETE FROM structured_memory")
    suspend fun deleteAll()

    @Query("UPDATE structured_memory SET access_count = access_count + 1, last_accessed_at = :accessedAt WHERE id = :id")
    suspend fun incrementAccessCount(
        id: Long,
        accessedAt: String,
    )

    @Query(
        "UPDATE structured_memory SET access_count = access_count + 1, last_accessed_at = :accessedAt " +
            "WHERE id IN (:ids)",
    )
    suspend fun incrementAccessCount(
        ids: List<Long>,
        accessedAt: String,
    )

    @Query("SELECT COUNT(*) FROM structured_memory WHERE owner_id = :ownerId")
    suspend fun getCount(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM structured_memory WHERE owner_id = :ownerId AND character_id = :characterId")
    suspend fun getCountByCharacter(
        ownerId: String,
        characterId: String,
    ): Int

    /** 备份导出用：一次性全表读取（不限 owner） */
    @Query("SELECT * FROM structured_memory")
    suspend fun getAllOnce(): List<com.mistbell.tavern.android.data.local.entity.StructuredMemoryEntity>
}
