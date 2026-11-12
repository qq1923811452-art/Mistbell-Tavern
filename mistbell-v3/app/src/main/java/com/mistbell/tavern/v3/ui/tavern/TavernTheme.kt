@file:Suppress("MatchingDeclarationName")

package com.mistbell.tavern.v3.ui.tavern

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale tuned for a visual-novel feel: serif display faces for page titles, a compact sans
 * body for chat prose. Bubble text is deliberately smaller than Material's default so a long
 * Chinese reply fits more lines per screen.
 */
object TavernTypography {
    val Scale =
        Typography(
            headlineLarge =
                TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    letterSpacing = 1.sp,
                ),
            headlineMedium =
                TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    letterSpacing = 1.sp,
                ),
            titleLarge =
                TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                ),
            titleMedium =
                TextStyle(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
        )
}

/** Dark night-tavern Material theme. Every top-level screen must sit inside this. */
@Suppress("FunctionNaming")
@Composable
fun TavernTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TavernColorScheme,
        typography = TavernTypography.Scale,
        content = content,
    )
}
