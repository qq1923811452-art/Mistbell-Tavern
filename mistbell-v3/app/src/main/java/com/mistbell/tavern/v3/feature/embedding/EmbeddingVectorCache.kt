package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

/** Process-wide 512-entry LRU. Entries are written only after successful inference validation. */
object EmbeddingVectorCache {
    const val CAPACITY = 512
    data class Entry(val vector: List<Float>, val status: InferenceRuntimeStatus)

    @Volatile private var logger: (String, String) -> Unit = { _, _ -> }

    private val entries = object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CAPACITY
    }

    @Synchronized
    fun get(key: String): Entry? = entries[key]?.let { it.copy(vector = it.vector.toList()) }

    @Synchronized
    fun put(key: String, vector: List<Float>, status: InferenceRuntimeStatus) {
        require(vector.isNotEmpty() && vector.all(Float::isFinite))
        entries[key] = Entry(vector.toList(), status)
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size

    fun setLogger(value: (String, String) -> Unit) { logger = value }
    fun log(event: String, detail: String) = logger(event, detail)

    /** Hash task-formatted text so query/document prefixes cannot collide. */
    fun key(text: String, modelId: String, dimensions: Int): String {
        require(modelId.isNotBlank() && dimensions > 0)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$digest|$modelId|$dimensions"
    }
}
