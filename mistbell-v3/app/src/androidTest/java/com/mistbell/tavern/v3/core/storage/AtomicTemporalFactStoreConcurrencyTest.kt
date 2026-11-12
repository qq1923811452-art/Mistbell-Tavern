package com.mistbell.tavern.v3.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Two store instances must serialize read-modify-write on the same durable file. */
@RunWith(AndroidJUnit4::class)
class AtomicTemporalFactStoreConcurrencyTest {
    @Test
    fun concurrentUpdatesFromSeparateInstancesLoseNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "facts-concurrency-${System.nanoTime()}.bin"
        val stores = listOf(AtomicTemporalFactStore(context, name), AtomicTemporalFactStore(context, name))
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val updates =
                (0 until WRITES).map { index ->
                    pool.submit {
                        stores[index % stores.size].update { existing -> existing + fact(index) }
                    }
                }
            updates.forEach { it.get(30, TimeUnit.SECONDS) }
            val loaded = AtomicTemporalFactStore(context, name).load()
            val facts = (loaded as TemporalFactLoadResult.Loaded).facts
            assertEquals(WRITES, facts.size)
            assertEquals(WRITES, facts.map(TemporalMemoryFact::id).distinct().size)
        } finally {
            pool.shutdownNow()
            File(context.filesDir, name).delete()
            File(context.filesDir, "$name.bak").delete()
            File(context.filesDir, "$name.new").delete()
        }
    }

    private fun fact(index: Int) =
        TemporalMemoryFact(
            id = "fact-$index",
            conversationId = "conversation",
            sourceTurnId = "turn-$index",
            subject = "user",
            predicate = "likes",
            value = "value-$index",
            observedAtEpochMillis = index.toLong(),
            validFromEpochMillis = index.toLong(),
        )

    private companion object {
        const val THREADS = 8
        const val WRITES = 40
    }
}
