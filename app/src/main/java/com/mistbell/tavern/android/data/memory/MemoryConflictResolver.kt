package com.mistbell.tavern.android.data.memory

import com.mistbell.tavern.android.data.api.model.StructuredMemory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Temporal fact handling without a destructive schema migration.
 *
 * Existing structured_data JSON remains readable. A changed value for the same
 * subject/relation marks the old fact as superseded, preserving its history while
 * keeping it out of current-context retrieval.
 */
internal object MemoryConflictResolver {
    private data class Fact(val subject: String, val relation: String, val value: String)

    fun isSuperseded(memory: StructuredMemory): Boolean =
        readObject(memory.structuredData)?.get("status")?.jsonPrimitive?.contentOrNull == "superseded"

    fun findSuperseded(
        subject: String,
        relation: String,
        value: String,
        existing: List<StructuredMemory>,
    ): List<StructuredMemory> {
        val keySubject = normalize(subject)
        val keyRelation = normalize(relation)
        val keyValue = normalize(value)
        if (keySubject.isBlank() || keyRelation.isBlank() || keyValue.isBlank()) return emptyList()

        return existing.filter { memory ->
            if (isSuperseded(memory)) return@filter false
            val fact = readFact(memory.structuredData) ?: return@filter false
            normalize(fact.subject) == keySubject &&
                normalize(fact.relation) == keyRelation &&
                normalize(fact.value).isNotBlank() &&
                normalize(fact.value) != keyValue
        }
    }

    fun conflictsWith(
        subject: String,
        relation: String,
        value: String,
        memory: StructuredMemory,
    ): Boolean = findSuperseded(subject, relation, value, listOf(memory)).isNotEmpty()

    fun markSuperseded(
        memory: StructuredMemory,
        replacement: String,
        now: String = Instant.now().toString(),
    ): StructuredMemory {
        val oldObject = readObject(memory.structuredData)
        val updatedJson = buildJsonObject {
            oldObject?.forEach { (key, value) -> put(key, value) }
            put("status", "superseded")
            put("supersededBy", replacement)
            put("supersededAt", now)
        }
        return memory.copy(structuredData = updatedJson.toString(), updatedAt = now)
    }

    private fun readFact(raw: String?): Fact? {
        val obj = readObject(raw)
        val subject = obj?.get("subject")?.jsonPrimitive?.contentOrNull.orEmpty()
        val relation = obj?.get("relation")?.jsonPrimitive?.contentOrNull.orEmpty()
        val value = obj?.get("object")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (obj == null) return null
        if (subject.isBlank() || relation.isBlank() || value.isBlank()) return null
        return Fact(subject, relation, value)
    }

    private fun readObject(raw: String?) =
        try {
            raw?.let { Json.parseToJsonElement(it).jsonObject }
        } catch (_: Exception) {
            null
        }

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]+"), "")
            .trim()
}
