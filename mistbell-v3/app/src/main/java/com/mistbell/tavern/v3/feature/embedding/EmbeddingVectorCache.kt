package com.mistbell.tavern.v3.feature.embedding

import com.mistbell.tavern.v3.core.inference.InferenceRuntimeStatus
import java.nio.charset.StandardCharsets
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

/** Process-wide 512-entry LRU. Entries are written only after successful inference validation. */
object EmbeddingVectorCache {
    const val CAPACITY = 512
    data class Entry(val vector: List<Float>, val status: InferenceRuntimeStatus, val policyVersion: Int)

    @Volatile private var logger: (String, String) -> Unit = { _, _ -> }

    private val entries = object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CAPACITY
    }

    @Synchronized
    fun get(key: String): Entry? = entries[key]?.let { it.copy(vector = it.vector.toList()) }

    @Synchronized
    fun remove(key: String) { entries.remove(key) }

    @Synchronized
    fun put(key: String, vector: List<Float>, status: InferenceRuntimeStatus, policyVersion: Int = EmbeddingGemma2TextPolicy.POLICY_VERSION) {
        require(vector.isNotEmpty() && vector.all(Float::isFinite))
        require(policyVersion > 0)
        entries[key] = Entry(vector.toList(), status, policyVersion)
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size

    fun setLogger(value: (String, String) -> Unit) { logger = value }
    fun log(event: String, detail: String) = logger(event, detail)

    /** Hash task-formatted text so query/document prefixes cannot collide. */
    fun key(
        text: String,
        modelId: String,
        dimensions: Int,
        policyVersion: Int = EmbeddingGemma2TextPolicy.POLICY_VERSION,
        modelProfileId: String = modelId,
    ): String {
        require(modelId.isNotBlank() && dimensions > 0 && policyVersion > 0 && modelProfileId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$digest|$modelId|$dimensions|$policyVersion|$modelProfileId"
    }

    /** Stable identity for the exact installed model profile without loading its native runtime. */
    fun modelProfileId(modelFile: File, npuModelCompatible: Boolean): String =
        "${modelFile.canonicalPath}|${modelFile.length()}|${modelFile.lastModified()}|$npuModelCompatible"
}
