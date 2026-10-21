package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mistbell.tavern.v3.feature.character.StoredCharacterCard

@Composable
internal fun ChatLibraryLoadingLifecycle(
    context: Context,
    coordinator: ChatLibraryLoadingCoordinator,
    selectedCharacterId: String,
    setSelectedCharacterId: (String) -> Unit,
    setCharacterCards: (List<StoredCharacterCard>) -> Unit,
) {
    LaunchedEffect(context) {
        val cards = withContext(Dispatchers.IO) {
            coordinator.loadCharacterCards()
        }
        setCharacterCards(cards)
        if (selectedCharacterId.isNotBlank() && cards.none { it.id == selectedCharacterId }) {
            setSelectedCharacterId("")
        }
    }

}
