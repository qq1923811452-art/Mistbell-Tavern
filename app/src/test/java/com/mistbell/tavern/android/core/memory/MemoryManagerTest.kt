package com.mistbell.tavern.android.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryManagerTest {
    @Test
    fun `recent returns newest memories first and respects limit`() = runBlocking {
        val store = FakeMemoryStore(
            listOf(
                MemoryRecord(id = "older", content = "older memory", createdAt = OLDER_TIMESTAMP),
                MemoryRecord(id = "newest", content = "newest memory", createdAt = NEWEST_TIMESTAMP),
                MemoryRecord(id = "middle", content = "middle memory", createdAt = MIDDLE_TIMESTAMP),
            ),
        )
        val manager = MemoryManager(store)

        assertEquals(
            listOf("newest", "middle"),
            manager.recent(2).map { it.id },
        )
    }

    @Test
    fun `recent with non positive limit returns empty list`() = runBlocking {
        val manager = MemoryManager(
            FakeMemoryStore(
                listOf(
                    MemoryRecord(id = "memory", content = "memory"),
                ),
            ),
        )

        assertEquals(emptyList<MemoryRecord>(), manager.recent(0))
        assertEquals(emptyList<MemoryRecord>(), manager.recent(-1))
    }

    @Test
    fun `clear delegates to storage`() = runBlocking {
        val store = FakeMemoryStore(
            listOf(MemoryRecord(id = "memory", content = "memory")),
        )
        val manager = MemoryManager(store)

        manager.clear()

        assertEquals(emptyList<MemoryRecord>(), manager.recent())
    }

    private class FakeMemoryStore(
        private val initial: List<MemoryRecord>,
    ) : MemoryStore {
        private val records = initial.toMutableList()

        override suspend fun save(memory: MemoryRecord) {
            records.removeAll { it.id == memory.id }
            records.add(memory)
        }

        override suspend fun getAll(): List<MemoryRecord> = records.toList()

        override suspend fun clear() {
            records.clear()
        }
    }

    private companion object {
        const val OLDER_TIMESTAMP = 1L
        const val MIDDLE_TIMESTAMP = 2L
        const val NEWEST_TIMESTAMP = 3L
    }
}
