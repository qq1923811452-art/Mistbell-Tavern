package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry
import com.mistbell.tavern.v3.feature.worldbook.WorldBookParser
import java.io.File

/**
 * Owns loading and parsing the currently selected local worldbook file.
 */
internal class ChatWorldBookLibraryLoader(
    private val context: Context,
    private val diagnosticStore: DiagnosticLogStore,
) {
    fun load(activeWorldbookId: String): Result<List<WorldBookEntry>> = runCatching {
        val directory = File(context.filesDir, "worldbooks")
        if (activeWorldbookId.isBlank()) {
            emptyList()
        } else {
            directory.listFiles { file ->
                file.isFile && file.extension == "json" && file.nameWithoutExtension == activeWorldbookId
            }.orEmpty().flatMap { file ->
                runCatching {
                    require(file.length() <= MAX_WORLDBOOK_FILE_BYTES) {
                        "Worldbook file exceeds the size limit."
                    }
                    WorldBookParser.parse(
                        AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) },
                        file.nameWithoutExtension,
                    )?.entries.orEmpty()
                }.getOrElse { error ->
                    diagnosticStore.warn(
                        "worldbook.file_skipped",
                        "book_id=$activeWorldbookId reason=${error::class.java.simpleName}",
                    )
                    emptyList()
                }
            }
        }
    }
}

private const val MAX_WORLDBOOK_FILE_BYTES = 16L * 1024 * 1024
