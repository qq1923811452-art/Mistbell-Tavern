package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun SwipeControls(
    index: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= 1) {
        return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = index > 0,
            modifier = Modifier.semantics { contentDescription = "上一个版本" },
        ) {
            Text(text = "‹", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = "${index + 1}/$total",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onNext,
            enabled = index < total - 1,
            modifier = Modifier.semantics { contentDescription = "下一个版本" },
        ) {
            Text(text = "›", style = MaterialTheme.typography.titleLarge)
        }
    }
}
