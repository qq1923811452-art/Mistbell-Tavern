package com.mistbell.tavern.v3.core.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupManifestTest {
    private val hash = "a".repeat(64)

    @Test
    fun manifestRoundTripsAndPreservesEntryMetadata() {
        val manifest = BackupManifest(
            version = 1,
            createdAt = "2026-10-21T12:00:00Z",
            appVersion = "3.0.0-dev",
            entryCount = 1,
            totalBytes = 3,
            entries = listOf(BackupEntry("data/settings.json", 3, hash)),
        )
        val parsed = BackupManifest.parse(manifest.toJson())
        assertEquals(manifest, parsed)
        BackupValidator.validateManifest(parsed)
    }

    @Test
    fun rejectsUnsupportedVersionAndCountMismatch() {
        val valid = BackupManifest(
            1, "2026-10-21T12:00:00Z", "test", 1, 3,
            listOf(BackupEntry("data/settings.json", 3, hash)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(valid.copy(version = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(valid.copy(entryCount = 2))
        }
    }

    @Test
    fun roundTripsMultipleEntriesAndLongSizes() {
        val entries = listOf(
            BackupEntry("data/settings.json", 0, hash),
            BackupEntry("data/memory/mistbell-v3-vectors.bin", BackupValidator.MAX_ENTRY_BYTES, hash),
        )
        val manifest = BackupManifest(
            version = BackupManifest.CURRENT_VERSION,
            createdAt = "2026-10-22T00:00:00Z",
            appVersion = "3.0.0-dev",
            entryCount = entries.size,
            totalBytes = entries.sumOf(BackupEntry::size),
            entries = entries,
        )
        val json = manifest.toJson()
        val parsed = BackupManifest.parse(json)
        assertEquals(manifest, parsed)

        val root = JSONObject(json)
        assertEquals(BackupManifest.FORMAT, root.getString("format"))
        assertEquals(BackupManifest.CURRENT_VERSION, root.getInt("version"))
        assertEquals(2, root.getInt("entryCount"))
        assertEquals(BackupValidator.MAX_ENTRY_BYTES, root.getLong("totalBytes"))
        assertEquals(2, root.getJSONArray("entries").length())
    }

    @Test
    fun parseRejectsUnsupportedFormat() {
        val json = JSONObject(validManifest().toJson()).put("format", "other-format")
        assertThrows(IllegalArgumentException::class.java) {
            BackupManifest.parse(json.toString())
        }
    }

    @Test
    fun parsedManifestWithUnsupportedVersionFailsValidation() {
        val json = JSONObject(validManifest().toJson()).put("version", 2).toString()
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validateManifest(BackupManifest.parse(json))
        }
    }

    @Test
    fun parseRejectsMalformedSha256InEntry() {
        listOf(
            "",
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ).forEach { invalidSha ->
            val entries = JSONArray().put(
                JSONObject()
                    .put("path", "data/settings.json")
                    .put("size", 0)
                    .put("sha256", invalidSha),
            )
            val json = JSONObject(validManifest().toJson()).put("entries", entries).toString()
            assertThrows(IllegalArgumentException::class.java) {
                BackupManifest.parse(json)
            }
        }
    }

    @Test
    fun parseRejectsMalformedJsonMissingFieldsAndWrongTypes() {
        listOf("not json", "[]", "{}").forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupManifest.parse(json)
            }
        }

        val missingField = JSONObject(validManifest().toJson())
        missingField.remove("createdAt")
        assertThrows(IllegalArgumentException::class.java) {
            BackupManifest.parse(missingField.toString())
        }

        val wrongSizeType = JSONObject(validManifest().toJson())
        wrongSizeType.getJSONArray("entries").getJSONObject(0).put("size", "three")
        assertThrows(IllegalArgumentException::class.java) {
            BackupManifest.parse(wrongSizeType.toString())
        }
    }

    private fun validManifest(): BackupManifest = BackupManifest(
        version = BackupManifest.CURRENT_VERSION,
        createdAt = "2026-10-22T00:00:00Z",
        appVersion = "3.0.0-dev",
        entryCount = 1,
        totalBytes = 3,
        entries = listOf(BackupEntry("data/settings.json", 3, hash)),
    )
}
