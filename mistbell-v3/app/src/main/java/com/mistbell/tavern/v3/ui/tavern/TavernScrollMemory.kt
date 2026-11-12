@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.ui.tavern

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * Remembers where each top-level page was scrolled to.
 *
 * Screens in this app are swapped with plain `if` branches, so leaving a page destroys its
 * composable and takes `rememberScrollState` / `rememberLazyListState` with it. The user then
 * returns to the previous page at the very top, which is what this fixes: each page writes its
 * offset back on dispose and reads it on the next composition.
 *
 * State is in-process only — a process restart starts every page at the top, which is the expected
 * behaviour for a reader.
 */
object TavernScrollMemory {
    private val scrollOffsets = HashMap<String, Int>()
    private val listPositions = HashMap<String, Pair<Int, Int>>()

    fun putScroll(
        key: String,
        offset: Int,
    ) {
        scrollOffsets[key] = offset
    }

    fun scroll(key: String): Int = scrollOffsets[key] ?: 0

    fun putList(
        key: String,
        index: Int,
        offset: Int,
    ) {
        listPositions[key] = index to offset
    }

    fun list(key: String): Pair<Int, Int> = listPositions[key] ?: (0 to 0)
}

/** `rememberScrollState` that survives leaving and re-entering the page. */
@Composable
fun rememberTavernScrollState(key: String): ScrollState {
    val initial = remember(key) { TavernScrollMemory.scroll(key) }
    val state = rememberScrollState(initial)
    DisposableEffect(key) {
        onDispose { TavernScrollMemory.putScroll(key, state.value) }
    }
    return state
}

/** `rememberLazyListState` that survives leaving and re-entering the page. */
@Composable
fun rememberTavernListState(key: String): LazyListState {
    val saved = remember(key) { TavernScrollMemory.list(key) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState(saved.first, saved.second)
    DisposableEffect(key) {
        onDispose {
            TavernScrollMemory.putList(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }
    return state
}
