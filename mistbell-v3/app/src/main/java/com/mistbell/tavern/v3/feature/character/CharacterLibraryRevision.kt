package com.mistbell.tavern.v3.feature.character

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * In-process revision counter for the character library.
 *
 * Cards are edited on a different screen than the chat surface, so readers key their load effect on
 * this counter instead of re-reading the library on every recomposition. It also covers the art
 * files: replacing an avatar must re-run the loader so session rows pick up the new picture.
 */
object CharacterLibraryRevision {
    var value by mutableStateOf(0)
        private set

    fun bump() {
        value += 1
    }
}
