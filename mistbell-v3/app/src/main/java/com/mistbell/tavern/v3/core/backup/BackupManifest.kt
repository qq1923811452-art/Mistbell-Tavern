package com.mistbell.tavern.v3.core.backup

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, self-describing metadata stored as the first entry of every backup ZIP. */
data class BackupManifest(
    val version: Int,
    val createdAt: String,
    val appVersion: String,
    val entryCount: Int,
    val totalBytes: Long,
    val entries: List<BackupEntry>,
    val format: String = FORMAT,
) {
    fun toJson(): String = JSONObject()
        .put("format", format)
        .put("version", version)
        .put("createdAt", createdAt)
        .put("appVersion", appVersion)
        .put("entryCount", entryCount)
        .put("totalBytes", totalBytes)
        .put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject()
                    .put("path", entry.path)
                    .put("size", entry.size)
                    .put("sha256", entry.sha256))
            }
        })
        .toString()

    companion object {
        const val FORMAT = "mistbell-backup"
        const val CURRENT_VERSION = 1

        fun parse(json: String): BackupManifest {
            try {
                val root = JSONObject(json)
                require(root.getString("format") == FORMAT) { "Unsupported backup format." }
                val parsedEntries = root.getJSONArray("entries")
                val entries = (0 until parsedEntries.length()).map { index ->
                    val entry = parsedEntries.getJSONObject(index)
                    BackupEntry(
                        path = entry.getString("path"),
                        size = entry.getLong("size"),
                        sha256 = entry.getString("sha256"),
                    )
                }
                return BackupManifest(
                    version = root.getInt("version"),
                    createdAt = root.getString("createdAt"),
                    appVersion = root.getString("appVersion"),
                    entryCount = root.getInt("entryCount"),
                    totalBytes = root.getLong("totalBytes"),
                    entries = entries,
                )
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalArgumentException("Backup manifest is invalid.", failure)
            }
        }
    }
}
