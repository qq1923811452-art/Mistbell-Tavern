package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import com.mistbell.tavern.v3.feature.character.CharacterCardLibraryStore
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard
import java.io.File

/**
 * Owns loading the existing app-private character card library.
 */
internal class ChatCharacterCardLibraryLoader(
    private val context: Context,
) {
    fun load(): List<StoredCharacterCard> = runCatching {
        CharacterCardLibraryStore(File(context.filesDir, "characters")).list()
    }.getOrDefault(emptyList())
}
