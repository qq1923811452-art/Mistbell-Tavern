package com.mistbell.tavern.v3.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.feature.chat.ChatMemoryApiSettingsSection

/** Memory extraction endpoint and credential, independent from the chat API. */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) {
        context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
    }
    val credentialStore = remember(context) { AndroidKeystoreMemoryCredentialStore(context) }
    val diagnosticStore = remember(context) { DiagnosticLogStore(context.filesDir) }
    var baseUrl by remember { mutableStateOf(settings.getString("memory_api_base_url", "https://api.odys.work/v1").orEmpty()) }
    var modelId by remember { mutableStateOf(settings.getString("memory_model_id", "").orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var hasSaved by remember {
        mutableStateOf(
            settings.getString("memory_api_credential_handle", null)
                ?.let(credentialStore::contains) == true,
        )
    }
    var extractionEnabled by remember {
        mutableStateOf(settings.getBoolean("memory_extraction_enabled", true))
    }
    var status by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆整理 API") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ChatMemoryApiSettingsSection(
                settings = settings,
                baseUrl = baseUrl,
                modelId = modelId,
                apiKey = apiKey,
                hasSavedCredential = hasSaved,
                credentialStore = credentialStore,
                diagnosticStore = diagnosticStore,
                onBaseUrlChange = { baseUrl = it },
                onModelIdChange = { modelId = it },
                onApiKeyChange = { apiKey = it },
                onSavedCredentialChange = { hasSaved = it },
                onStatusChange = { status = it },
                extractionEnabled = extractionEnabled,
                onExtractionEnabledChange = { enabled ->
                    extractionEnabled = enabled
                    settings.edit().putBoolean("memory_extraction_enabled", enabled).apply()
                },
            )
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
