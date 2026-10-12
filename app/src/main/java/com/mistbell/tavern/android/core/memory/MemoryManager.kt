package com.mistbell.tavern.android.core.memory

/**
 * Central coordinator for Mistbell 2.0 memory features.
 *
 * This layer intentionally stays storage-agnostic so it can later connect
 * Room, DataStore or vector retrieval without changing chat logic.
 */
class MemoryManager(
    private val store: MemoryStore
) {
    suspend fun save(record: MemoryRecord) {
        store.save(record)
    }

    suspend fun recent(limit: Int = 20): List<MemoryRecord> {
        return store.query(limit)
    }

    suspend fun clear() {
        store.clear()
    }
}
