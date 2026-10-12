package com.mistbell.tavern.android.core.memory

/**
 * Storage abstraction for Mistbell 2.0 memory system.
 *
 * The first implementation is intentionally lightweight so it can later
 * be backed by Room, DataStore or vector storage without changing callers.
 */
interface MemoryStore {
    suspend fun save(memory: MemoryRecord)

    suspend fun getAll(): List<MemoryRecord>

    suspend fun clear()
}
