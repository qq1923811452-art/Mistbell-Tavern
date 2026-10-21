package com.mistbell.tavern.v3.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns small chat-screen state that should survive configuration changes and may be restored
 * after process recreation. The conversation history and API credentials are intentionally not
 * stored here.
 */
class ChatViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(draft = savedStateHandle[KEY_DRAFT] ?: ""),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun updateDraft(value: String) {
        savedStateHandle[KEY_DRAFT] = value
        _uiState.update { current -> current.copy(draft = value) }
    }

    fun clearDraft() {
        savedStateHandle[KEY_DRAFT] = ""
        _uiState.update { current -> current.copy(draft = "") }
    }

    private companion object {
        const val KEY_DRAFT = "chat.draft"
    }
}
