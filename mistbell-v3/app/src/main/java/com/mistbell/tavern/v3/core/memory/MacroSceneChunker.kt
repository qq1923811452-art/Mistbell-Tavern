package com.mistbell.tavern.v3.core.memory

import java.security.MessageDigest

/** One completed user/assistant pair used for macro scene summaries. */
data class MacroDialogueTurn(
    val turnId: String,
    val userText: String,
    val assistantText: String,
)

/**
 * Groups completed dialogue turns into macro scenes and splits each scene into overlapping chunks.
 * Records use the `scene:` prefix so the fact-text synchronizer preserves them.
 */
object MacroSceneChunker {
    fun chunkDialogueTurns(
        conversationId: String,
        turns: List<MacroDialogueTurn>,
        targetMinChars: Int = DEFAULT_MIN_CHARS,
        targetMaxChars: Int = DEFAULT_MAX_CHARS,
        overlapTurns: Int = DEFAULT_OVERLAP_TURNS,
    ): List<MemoryTextRecord> {
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank." }
        require(targetMinChars > 0 && targetMaxChars >= targetMinChars) { "Chunk character budget is invalid." }
        require(overlapTurns >= 0) { "Overlap turns must not be negative." }
        val records = mutableListOf<MemoryTextRecord>()
        turns.chunked(SCENE_TURNS).forEach { scene ->
            if (scene.size < SCENE_TURNS) return@forEach
            val groupId = sceneGroupId(conversationId, scene)
            val summary = compactSummary(scene)
            splitScene(scene, targetMinChars, targetMaxChars, overlapTurns).forEachIndexed { index, chunk ->
                val body = chunk.joinToString("\n") { "User: ${it.userText}\nAssistant: ${it.assistantText}" }
                records +=
                    MemoryTextRecord(
                        id = "$SCENE_PREFIX$groupId:chunk:$index",
                        text = "[剧情脉络] $summary\n[片段] $body",
                    )
            }
        }
        return records
    }

    private fun splitScene(
        scene: List<MacroDialogueTurn>,
        targetMinChars: Int,
        targetMaxChars: Int,
        overlapTurns: Int,
    ): List<List<MacroDialogueTurn>> {
        val chunks = mutableListOf<List<MacroDialogueTurn>>()
        var start = 0
        while (start < scene.size) {
            val chunk = mutableListOf<MacroDialogueTurn>()
            var chars = 0
            var cursor = start
            while (cursor < scene.size) {
                val length = turnText(scene[cursor]).length
                if (chunk.isNotEmpty() && chars >= targetMinChars && chars + length > targetMaxChars) break
                chunk += scene[cursor]
                chars += length
                cursor++
            }
            if (chunk.isEmpty()) cursor = start + 1
            chunks += chunk
            if (cursor >= scene.size) break
            start = maxOf(start + 1, cursor - overlapTurns)
        }
        return chunks
    }

    private fun compactSummary(scene: List<MacroDialogueTurn>): String {
        val picks =
            listOf(scene.first(), scene[scene.size / 2], scene.last())
                .distinctBy(MacroDialogueTurn::turnId)
        return picks.joinToString(" / ") { turn ->
            "${turn.userText.take(SUMMARY_PART_CHARS)}→${turn.assistantText.take(SUMMARY_PART_CHARS)}"
        }.take(SUMMARY_MAX_CHARS)
    }

    private fun sceneGroupId(
        conversationId: String,
        scene: List<MacroDialogueTurn>,
    ): String {
        val identity = conversationId + "|" + scene.joinToString("|") { it.turnId }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(GROUP_HASH_CHARS)
    }

    private fun turnText(turn: MacroDialogueTurn): String = "User: ${turn.userText}\nAssistant: ${turn.assistantText}"

    private const val SCENE_TURNS = 6
    private const val DEFAULT_MIN_CHARS = 450
    private const val DEFAULT_MAX_CHARS = 600
    private const val DEFAULT_OVERLAP_TURNS = 1
    private const val SUMMARY_MAX_CHARS = 350
    private const val SUMMARY_PART_CHARS = 80
    private const val GROUP_HASH_CHARS = 16
    private const val SCENE_PREFIX = "scene:"
}
