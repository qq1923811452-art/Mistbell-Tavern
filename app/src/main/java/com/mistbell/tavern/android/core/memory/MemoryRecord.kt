package com.mistbell.tavern.android.core.memory

/**
 * Mistbell 2.0 memory foundation.
 * Stores a reusable memory record for future conversation context injection.
 */
data class MemoryRecord(
    val id: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val importance: Int = 0
)
