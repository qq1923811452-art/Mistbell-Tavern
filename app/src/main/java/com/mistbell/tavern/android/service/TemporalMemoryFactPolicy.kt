package com.mistbell.tavern.android.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Temporal metadata lives in structured_data JSON so this policy does not require a Room migration.
 * Bump/extend the JSON keys here rather than adding columns for each temporal attribute.
 */
internal @Suppress("TooManyFunctions")
object TemporalMemoryFactPolicy {
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
        val subject = data.stringValue("subject").orEmpty()
        val relation = data.stringValue("relation").orEmpty()
        val objectValue = data.stringValue("object").orEmpty()
        return if (subject.isBlank() || relation.isBlank() || objectValue.isBlank()) {
            null
        } else {
            Fact(
                id = id,
                subject = subject,
                relation = relation,
                objectValue = objectValue,
                status = data.stringValue("status").orEmpty().ifBlank { CURRENT },
                validTo = data.stringValue("valid_to").orEmpty(),
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

    /** Whether a later candidate in the same extraction batch supersedes a staged current fact. */
    fun conflictsWithStagedFact(
        subject: String,
        relation: String,
        objectValue: String,
        content: String,
        stagedFact: Fact,
    ): Boolean =
        shouldSupersede(relation, content) &&
            normalize(subject) == normalize(stagedFact.subject) &&
            normalize(relation) == normalize(stagedFact.relation) &&
            normalize(objectValue) != normalize(stagedFact.objectValue)

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
        return data.stringValue("status") == HISTORICAL || data.stringValue("valid_to").orEmpty().isNotBlank()
    }

    fun markCurrent(
        structuredData: String,
        validFrom: String,
        supersededIds: List<Long>,
        previousValues: List<String>,
    ): String {
        val data = decode(structuredData).toMutableMap()
        data["status"] = JsonPrimitive(CURRENT)
        data["valid_from"] = JsonPrimitive(validFrom)
        data["valid_to"] = JsonPrimitive("")
        data["supersedes_ids"] = JsonPrimitive(supersededIds.distinct().joinToString(","))
        data["previous_values"] = JsonPrimitive(previousValues.distinct().joinToString(" | "))
        return Json.encodeToString(JsonObject(data))
    }

    fun markHistorical(
        structuredData: String?,
        validTo: String,
        supersededById: Long,
    ): String {
        val data = decode(structuredData.orEmpty()).toMutableMap()
        data["status"] = JsonPrimitive(HISTORICAL)
        data["valid_to"] = JsonPrimitive(validTo)
        data["superseded_by"] = JsonPrimitive(supersededById.toString())
        return Json.encodeToString(JsonObject(data))
    }

    /**
     * Keep the full JSON tree instead of decoding Map<String, String>: legacy payloads may contain
     * numbers, arrays, booleans, or nested objects. A strict map decode silently replaced those
     * payloads with an empty map and destroyed every non-string field during temporal updates.
     * Invalid/non-object legacy data is preserved verbatim under legacy_raw before adding metadata.
     */
    private fun decode(structuredData: String): JsonObject {
        if (structuredData.isBlank()) return JsonObject(emptyMap())
        return runCatching {
            when (val element = Json.parseToJsonElement(structuredData)) {
                is JsonObject -> element
                else -> JsonObject(mapOf("legacy_raw" to JsonPrimitive(structuredData)))
            }
        }.getOrElse {
            JsonObject(mapOf("legacy_raw" to JsonPrimitive(structuredData)))
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

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
