package com.mistbell.tavern.v3.feature.character

import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class StoredCharacterCard(
    val id: String,
    val card: CharacterCard,
)

/**
 * Small, crash-safe private-file library for imported character cards.
 * Interchange stays in SillyTavern JSON; filenames use generated IDs, never user-provided names.
 */
class CharacterCardLibraryStore(private val directory: File) {
    fun list(): List<StoredCharacterCard> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { read(AtomicFile(file)) }
                    .onFailure { error ->
                        Log.w("CharacterCardLibrary", "Skipping invalid character file ${file.name}", error)
                    }
                    .getOrNull()
            }
            .sortedWith(compareBy<StoredCharacterCard, String>(String.CASE_INSENSITIVE_ORDER) { item -> item.card.name }.thenBy { item -> item.id })
    }

    fun save(id: String?, card: CharacterCard): StoredCharacterCard {
        require(card.name.isNotBlank()) { "Character name is required." }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create the character library directory.")
        }
        val safeId = id?.takeIf { it.matches(ID_PATTERN) } ?: UUID.randomUUID().toString()
        val target = AtomicFile(File(directory, "$safeId.json"))
        val record = JSONObject()
            .put("id", safeId)
            .put("card", CharacterCardJsonCodec.export(card))
            .toString()
        var stream: FileOutputStream? = null
        try {
            val output = target.startWrite()
            stream = output
            output.write(record.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
            stream = null
        } catch (error: Exception) {
            stream?.let(target::failWrite)
            throw error
        }
        return StoredCharacterCard(safeId, card)
    }

    fun delete(id: String): Boolean {
        if (!id.matches(ID_PATTERN)) return false
        val file = File(directory, "$id.json")
        if (!file.isFile) return false
        AtomicFile(file).delete()
        return true
    }

    private fun read(file: AtomicFile): StoredCharacterCard {
        val raw = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        val record = JSONObject(raw)
        val id = record.getString("id")
        require(id.matches(ID_PATTERN)) { "Invalid stored character ID." }
        val card = CharacterCardJsonCodec.parse(record.getString("card"))
            ?: throw IllegalArgumentException("Stored character card is invalid.")
        return StoredCharacterCard(id, card)
    }

    companion object {
        private val ID_PATTERN = Regex("[a-fA-F0-9-]{36}")
    }
}
