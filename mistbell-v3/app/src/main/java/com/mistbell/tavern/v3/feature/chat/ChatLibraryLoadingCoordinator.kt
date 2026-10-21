package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard
import com.mistbell.tavern.v3.feature.worldbook.WorldBookEntry

/**
 * Groups the IO-bound loading operations for the local character and worldbook libraries.
 * UI state and selection reconciliation remain owned by ChatScreen.
 */
internal class ChatLibraryLoadingCoordinator(
    context: Context,
    private val diagnosticStore: DiagnosticLogStore,
) {
    private val applicationContext = context.applicationContext

    fun loadCharacterCards(): List<StoredCharacterCard> =
        ChatCharacterCardLibraryLoader(applicationContext).load()

    fun loadWorldbookEntries(activeWorldbookId: String): List<WorldBookEntry> {
        val loaded = ChatWorldBookLibraryLoader(applicationContext, diagnosticStore).load(activeWorldbookId)
        return loaded.getOrElse { error ->
            diagnosticStore.warn(
                "worldbook.load_failed",
                "book_id=$activeWorldbookId reason=${error::class.java.simpleName}",
            )
            emptyList()
        }
    }
}
