package com.mistbell.tavern.android.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Temporal metadata lives in structured_data JSON so this policy does not require a Room migration.
 * Bump/extend the JSON keys here rather than adding columns for each temporal attribute.
 */
internal object TemporalMemoryFactPolicy {
    private val singleValuedRelations = setOf("name", "role_is", "located_at", "title_is")

    data class Fact(
        val id: Long,
        val subject: String,
        val relation: String,
        val objectValue: String,
        val status: String = CURRENT,
        val validTo: String = "",
    )

    fun parseFact(id: Long, structuredData: String?): Fact? {
        if (structuredData.isNullOrBlank()) return null
        val data = decode(structuredData)
        val subject = data["subject"].orEmpty()
        val relation = data["relation"].orEmpty()
        val objectValue = data["object"].orEmpty()
        return if (subject.isBlank() || relation.isBlank() || objectValue.isBlank()) {
            null
        } else {
            Fact(
                id = id,
                subject = subject,
                relation = relation,
                objectValue = objectValue,
                status = data["status"].orEmpty().ifBlank { CURRENT },
                validTo = data["valid_to"].orEmpty(),
            )
        }
    }

    fun findRelatedFacts(
        subject: String,
        relation: String,
        objectValue: String,
        existingFacts: List<Fact>,
    ): List<Fact> {
        val normalizedSubject = normalize(subject)
        val normalizedRelation = normalize(relation)
        val normalizedObject = normalize(objectValue)
        val hasBlankFactKey =
            normalizedSubject.isBlank() || normalizedRelation.isBlank() || normalizedObject.isBlank()
        val unsupportedRelation = normalizedRelation in setOf("other", "unknown")
        if (hasBlankFactKey || unsupportedRelation) {
            return emptyList()
        }
        return existingFacts.filter { existing ->
            existing.status != HISTORICAL &&
                existing.validTo.isBlank() &&
                normalize(existing.subject) == normalizedSubject &&
                normalize(existing.relation) == normalizedRelation &&
                normalize(existing.objectValue) != normalizedObject
        }
    }

    fun shouldSupersede(relation: String, content: String): Boolean {
        val explicitlyUpdated =
            content.contains("已改为") ||
                content.contains("改成") ||
                content.contains("changed to", ignoreCase = true) ||
                content.contains("updated to", ignoreCase = true)
        return relation.trim().lowercase() in singleValuedRelations || explicitlyUpdated
    }

    fun findConflicts(
        subject: String,
        relation: String,
        objectValue: String,
        content: String,
        existingFacts: List<Fact>,
    ): List<Fact> =
        if (shouldSupersede(relation, content)) {
            findRelatedFacts(subject, relation, objectValue, existingFacts)
        } else {
            emptyList()
        }

    fun isHistorical(structuredData: String?): Boolean {
        if (structuredData.isNullOrBlank()) return false
        val data = decode(structuredData)
        return data["status"] == HISTORICAL || data["valid_to"].orEmpty().isNotBlank()
    }

    fun markCurrent(
        structuredData: String,
        validFrom: String,
        supersededIds: List<Long>,
        previousValues: List<String>,
    ): String {
        val data = decode(structuredData).toMutableMap()
        data["status"] = CURRENT
        data["valid_from"] = validFrom
        data["valid_to"] = ""
        data["supersedes_ids"] = supersededIds.distinct().joinToString(",")
        data["previous_values"] = previousValues.distinct().joinToString(" | ")
        return Json.encodeToString(data)
    }

    fun markHistorical(
        structuredData: String?,
        validTo: String,
        supersededById: Long,
    ): String {
        val data = decode(structuredData.orEmpty()).toMutableMap()
        data["status"] = HISTORICAL
        data["valid_to"] = validTo
        data["superseded_by"] = supersededById.toString()
        return Json.encodeToString(data)
    }

    private fun decode(structuredData: String): Map<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(structuredData) }.getOrDefault(emptyMap())

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(
                Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]+"),
                "",
            ).trim()

    private const val CURRENT = "current"
    private const val HISTORICAL = "historical"
}
