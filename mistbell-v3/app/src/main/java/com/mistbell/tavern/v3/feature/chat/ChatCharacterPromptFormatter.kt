package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.feature.character.CharacterCard

/** Keeps stable character-card prompt formatting outside the screen composable. */
internal object ChatCharacterPromptFormatter {
    fun format(card: CharacterCard): String = card.stableDefinition()
}
