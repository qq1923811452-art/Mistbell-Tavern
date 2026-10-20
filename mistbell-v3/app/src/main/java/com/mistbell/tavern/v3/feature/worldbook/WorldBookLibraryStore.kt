package com.mistbell.tavern.v3.feature.worldbook

import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Private-file library for worldbooks; import/export remains compatible with World Info JSON. */
class WorldBookLibraryStore(private val directory: File) {
    fun list(): List<WorldBook> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val parsed = WorldBookParser.parse(
                        AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) },
                        file.nameWithoutExtension,
                    ) ?: error("Invalid worldbook JSON")
                    // The library filename is the stable storage identity. This also repairs
                    // older files whose embedded id is absent or disagrees with the filename.
                    val fileId = file.nameWithoutExtension
                    parsed.withLibraryFileId(fileId)
                }.onFailure { error ->
                    Log.w("WorldBookLibrary", "Skipping invalid worldbook file ${file.name}", error)
                }.getOrNull()
            }
            .sortedWith(compareBy<WorldBook, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
    }

    fun save(book: WorldBook): WorldBook {
        require(book.name.isNotBlank()) { "Worldbook name is required." }
        val id = book.id.takeIf { it.matches(ID_PATTERN) } ?: UUID.randomUUID().toString()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create the worldbook directory.")
        }
        val normalized = book.copy(
            id = id,
            entries = book.entries.map { it.copy(bookId = id) },
        )
        val target = AtomicFile(File(directory, "$id.json"))
        val bytes = export(normalized).toByteArray(Charsets.UTF_8)
        var stream: FileOutputStream? = null
        try {
            val output = target.startWrite()
            stream = output
            output.write(bytes)
            target.finishWrite(output)
            stream = null
        } catch (error: Exception) {
            stream?.let(target::failWrite)
            throw error
        }
        return normalized
    }

    fun delete(id: String): Boolean {
        if (!id.matches(ID_PATTERN)) return false
        val file = File(directory, "$id.json")
        if (!file.isFile) return false
        AtomicFile(file).delete()
        return true
    }

    fun export(book: WorldBook): String {
        val entries = JSONObject()
        book.entries.forEach { entry ->
            val position = when (entry.insertPosition) {
                WorldBookPlacement.POSITION_AFTER_PROMPT -> 1
                WorldBookPlacement.POSITION_AN_BEFORE -> 2
                WorldBookPlacement.POSITION_AN_AFTER -> 3
                WorldBookPlacement.POSITION_DEPTH -> 4
                WorldBookPlacement.POSITION_BEFORE_PROMPT -> if (entry.depth > 0) 4 else 0
                WorldBookPlacement.POSITION_EM_BEFORE -> 5
                WorldBookPlacement.POSITION_EM_AFTER -> 6
                else -> 0
            }
            val value = JSONObject()
                .put("uid", entry.id)
                .put("comment", entry.comment)
                .put("key", JSONArray(entry.keys))
                .put("keysecondary", JSONArray(entry.secondaryKeys))
                .put("content", entry.content)
                .put("constant", entry.constant)
                .put("enabled", !entry.disable)
                .put("disable", entry.disable)
                .put("insertion_order", entry.order)
                .put("position", position)
                .put("depth", entry.depth)
                .put("probability", (entry.probability * 100).toInt().coerceIn(0, 100))
                .put("useProbability", true)
                .put("role", when (entry.depthRole) {
                    WorldBookPlacement.DEPTH_ROLE_USER -> 1
                    WorldBookPlacement.DEPTH_ROLE_ASSISTANT -> 2
                    else -> 0
                })
                .put("sticky", entry.sticky)
                .put("cooldown", entry.cooldown)
                .put("delay", entry.delay)
                .put("group", entry.groupName)
            entries.put(entry.id, value)
        }
        return JSONObject()
            .put("id", book.id)
            .put("name", book.name)
            .put("entries", entries)
            .toString(2)
    }

    companion object {
        private val ID_PATTERN = Regex("[a-fA-F0-9-]{36}")
    }
}


internal fun WorldBook.withLibraryFileId(fileId: String): WorldBook = copy(
    id = fileId,
    entries = entries.map { it.copy(bookId = fileId) },
)
