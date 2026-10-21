package com.mistbell.tavern.v3.feature.chat

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelTest {

    @Test
    fun restoresDraftFromSavedStateHandle() {
        val viewModel = ChatViewModel(SavedStateHandle(mapOf("chat.draft" to "hello")))

        assertEquals("hello", viewModel.uiState.value.draft)
    }

    @Test
    fun updateDraftUpdatesUiStateAndSavedState() {
        val savedState = SavedStateHandle()
        val viewModel = ChatViewModel(savedState)

        viewModel.updateDraft("new draft")

        assertEquals("new draft", viewModel.uiState.value.draft)
        assertEquals("new draft", savedState.get<String>("chat.draft"))
    }

    @Test
    fun clearDraftClearsUiStateAndSavedState() {
        val savedState = SavedStateHandle(mapOf("chat.draft" to "old draft"))
        val viewModel = ChatViewModel(savedState)

        viewModel.clearDraft()

        assertEquals("", viewModel.uiState.value.draft)
        assertEquals("", savedState.get<String>("chat.draft"))
    }
}
