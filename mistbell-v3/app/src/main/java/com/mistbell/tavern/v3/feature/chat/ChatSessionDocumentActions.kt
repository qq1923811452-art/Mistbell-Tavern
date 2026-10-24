package com.mistbell.tavern.v3.feature.chat

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mistbell.tavern.v3.feature.sessions.SessionKind
import com.mistbell.tavern.v3.feature.sessions.SessionParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

/**
 * Groups the system document-picker launchers and overwrite confirmation for session transfer.
 * The existing import/export coordinators retain validation, persistence, and serialization.
 */
internal data class ChatSessionDocumentCallbacks(
    val onImportSession: () -> Unit,
    val onExportJson: () -> Unit,
    val onExportJsonl: () -> Unit,
)

@Composable
@Suppress("LongParameterList")
internal fun rememberChatSessionDocumentActions(
    context: Context,
    coroutineScope: CoroutineScope,
    historyStore: SqliteChatHistoryRepository,
    historySaveMutex: Mutex,
    historySaveRevision: AtomicLong,
    history: MutableList<ChatLine>,
    conversationId: String,
    setStatus: (String) -> Unit,
    onImported: (SessionArchive) -> Unit = {},
    sessionTitle: String = "Mistbell Tavern session",
    sessionKind: SessionKind = SessionKind.SINGLE,
    sessionParticipants: List<SessionParticipant> = emptyList(),
): ChatSessionDocumentCallbacks {
    var showImportWarning by remember { mutableStateOf(false) }

    val importSessionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            ChatSessionImportCoordinator(
                context = context,
                coroutineScope = coroutineScope,
                historyStore = historyStore,
                historySaveMutex = historySaveMutex,
                historySaveRevision = historySaveRevision,
                history = history,
                setStatus = setStatus,
                onImported = onImported,
            ).import(uri)
        }
    }
    ChatSessionImportWarningDialog(
        visible = showImportWarning,
        onDismiss = { showImportWarning = false },
        onConfirm = {
            showImportWarning = false
            importSessionLauncher.launch(arrayOf("application/json", "application/jsonl", "text/plain", "*/*"))
        },
    )

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            ChatSessionExportCoordinator(context, coroutineScope, setStatus)
                .export(
                    uri, conversationId, history, jsonLines = false,
                    title = sessionTitle, kind = sessionKind,
                    participants = sessionParticipants,
                )
        }
    }
    val exportJsonlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/jsonl"),
    ) { uri ->
        if (uri != null) {
            ChatSessionExportCoordinator(context, coroutineScope, setStatus)
                .export(
                    uri, conversationId, history, jsonLines = true,
                    title = sessionTitle, kind = sessionKind,
                    participants = sessionParticipants,
                )
        }
    }

    return remember(importSessionLauncher, exportJsonLauncher, exportJsonlLauncher) {
        ChatSessionDocumentCallbacks(
            onImportSession = { showImportWarning = true },
            onExportJson = { exportJsonLauncher.launch("mistbell-session.json") },
            onExportJsonl = { exportJsonlLauncher.launch("mistbell-session.jsonl") },
        )
    }
}
