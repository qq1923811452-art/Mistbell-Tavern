package com.mistbell.tavern.v3.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.mistbell.tavern.v3.R

/**
 * Full-screen backdrop with a readability scrim. The scrim keeps chat bubbles legible regardless
 * of how bright the artwork is, so new backgrounds can be swapped without retuning text colors.
 */
@Suppress("FunctionNaming")
@Composable
fun TavernBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_tavern),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = SCRIM_TOP_ALPHA),
                        SCRIM_MIDDLE_STOP to Color.Black.copy(alpha = SCRIM_MIDDLE_ALPHA),
                        1.0f to Color.Black.copy(alpha = SCRIM_BOTTOM_ALPHA),
                    ),
                ),
        )
        content()
    }
}

private const val SCRIM_MIDDLE_STOP = 0.35f
private const val SCRIM_TOP_ALPHA = 0.45f
private const val SCRIM_MIDDLE_ALPHA = 0.25f
private const val SCRIM_BOTTOM_ALPHA = 0.55f
