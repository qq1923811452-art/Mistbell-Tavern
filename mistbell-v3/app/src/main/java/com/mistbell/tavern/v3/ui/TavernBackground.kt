package com.mistbell.tavern.v3.ui

import androidx.annotation.DrawableRes
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
 * Full-screen backdrop with a readability scrim. The scrim keeps prose legible regardless of how
 * bright the artwork is, so a new painting can be swapped in without retuning text colours.
 *
 * Floor and ceiling are darkened harder than the middle: the middle is where the prose lives.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun TavernBackground(
    modifier: Modifier = Modifier,
    @DrawableRes drawable: Int = R.drawable.bg_tavern,
    topAlpha: Float = SCRIM_TOP_ALPHA,
    middleAlpha: Float = SCRIM_MIDDLE_ALPHA,
    bottomAlpha: Float = SCRIM_BOTTOM_ALPHA,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(drawable),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = topAlpha),
                            SCRIM_MIDDLE_STOP to Color.Black.copy(alpha = middleAlpha),
                            1.0f to Color.Black.copy(alpha = bottomAlpha),
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
