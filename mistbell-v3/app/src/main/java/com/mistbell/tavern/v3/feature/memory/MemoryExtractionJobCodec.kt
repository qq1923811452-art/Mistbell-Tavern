package com.mistbell.tavern.v3.feature.memory

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Versioned deterministic encoding with a SHA-256 integrity trailer. */
object MemoryExtractionJobCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_JOBS = 2_000
    private const val VERSION = 1
    private const val CHECKSUM_SIZE = 32

    fun encode(jobs: List<MemoryExtractionJob>): ByteArray {
        require(jobs.size <= MAX_JOBS)
        require(jobs.map { it.id }.distinct().size == jobs.size)
        val root = JSONObject().put("version", VERSION).put(
            "jobs",
            JSONArray().also { array ->
                jobs.sortedBy { it.id }.forEach { job ->
                    array.put(
                        JSONObject()
                            .put("id", job.id)
                            .put("conversation", job.conversationId)
                            .put("turns", JSONArray(job.turnIds))
                            .put("provider", job.providerProfileId)
                            .put("credential_handle", job.credentialHandle)
                            .put("created", job.createdAtEpochMillis)
                            .put("attempts", job.attemptCount)
                            .put("next_attempt", job.nextAttemptAtEpochMillis)
                            .put("state", job.state.name)
                            .put("error", job.lastSafeError),
                    )
                }
            },
        )
        val payload = root.toString().toByteArray(Charsets.UTF_8)
        val encoded = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        require(encoded.size <= MAX_BYTES)
        return encoded
    }

    fun decode(encoded: ByteArray): List<MemoryExtractionJob> {
        require(encoded.size in (CHECKSUM_SIZE + 1)..MAX_BYTES) { "Queue file is truncated or too large." }
        val split = encoded.size - CHECKSUM_SIZE
        val payload = encoded.copyOfRange(0, split)
        val checksum = encoded.copyOfRange(split, encoded.size)
        require(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload))) {
            "Queue integrity check failed."
        }
        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.getInt("version") == VERSION) { "Queue format version is unsupported." }
        val array = root.getJSONArray("jobs")
        require(array.length() <= MAX_JOBS)
        val jobs = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val turns = item.getJSONArray("turns")
            require(turns.length() in 1..20)
            MemoryExtractionJob(
                id = item.getString("id"),
                conversationId = item.getString("conversation"),
                turnIds = (0 until turns.length()).map(turns::getString),
                providerProfileId = item.getString("provider"),
                credentialHandle = if (item.isNull("credential_handle")) null else item.optString("credential_handle").takeIf(String::isNotBlank),
                createdAtEpochMillis = item.getLong("created"),
                attemptCount = item.getInt("attempts"),
                nextAttemptAtEpochMillis = item.getLong("next_attempt"),
                state = MemoryExtractionJob.State.valueOf(item.getString("state")),
                lastSafeError = item.optString("error").takeIf(String::isNotBlank),
            )
        }
        require(jobs.map { it.id }.distinct().size == jobs.size)
        return jobs
    }
}
