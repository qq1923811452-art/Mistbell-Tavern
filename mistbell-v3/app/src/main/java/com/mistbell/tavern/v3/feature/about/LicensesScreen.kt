package com.mistbell.tavern.v3.feature.about

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Shows the bundled third-party notices so license obligations stay reachable in the app. */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        runCatching {
            context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
        }.getOrDefault("未找到许可文件。")
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开源许可与第三方声明") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            )
        },
    ) { padding ->
        Text(
            text = text,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}
