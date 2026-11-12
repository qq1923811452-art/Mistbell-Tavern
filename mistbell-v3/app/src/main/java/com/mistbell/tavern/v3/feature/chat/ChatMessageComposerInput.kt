@file:Suppress("FunctionNaming", "MagicNumber")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.ui.tavern.TavernFrostedSurface
import com.mistbell.tavern.v3.ui.tavern.TavernPalette

private val COMPOSER_SHAPE = RoundedCornerShape(26.dp)

/**
 * Composer dock. The field itself is transparent and sits inside a frosted pill, so the sheet of
 * glass is one continuous surface instead of a bordered box with a theme-coloured outline.
 */
@Composable
internal fun ChatMessageComposerInput(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TavernFrostedSurface(modifier = modifier.fillMaxWidth(), shape = COMPOSER_SHAPE) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("输入消息…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            enabled = enabled,
            minLines = 1,
            maxLines = 4,
            shape = COMPOSER_SHAPE,
            textStyle =
                androidx.compose.material3.LocalTextStyle.current.copy(
                    textAlign = TextAlign.Start,
                    color = TavernPalette.TextPrimary,
                ),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    cursorColor = TavernPalette.Purple,
                    focusedTextColor = TavernPalette.TextPrimary,
                    unfocusedTextColor = TavernPalette.TextPrimary,
                    focusedPlaceholderColor = TavernPalette.TextTertiary,
                    unfocusedPlaceholderColor = TavernPalette.TextTertiary,
                ),
        )
    }
}
