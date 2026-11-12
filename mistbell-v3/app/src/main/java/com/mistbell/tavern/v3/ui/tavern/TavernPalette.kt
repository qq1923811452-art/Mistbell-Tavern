@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.ui.tavern

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Night-tavern palette: one source of truth for every screen.
 *
 * The session list used to own its own copy of these colours while the rest of the app sat on the
 * light Material palette, which is why a light app bar floated above dark artwork. Every screen now
 * reads from here.
 */
object TavernPalette {
    val Night = Color(0xFF0B0710)
    val Ink = Color(0xFF120A1B)
    val DeepGrape = Color(0xFF150C22)
    val Surface = Color(0xFF171020)
    val SurfaceRaised = Color(0xFF241A33)
    val Purple = Color(0xFF9B6BFF)
    val PurpleSoft = Color(0xFFD8B4FE)
    val PurpleDeep = Color(0xFF5B21B6)
    val Fab = Color(0xFF8B5CF6)
    val Gold = Color(0xFFFFCE8A)
    val GoldDim = Color(0xFFD9A66C)
    val TextPrimary = Color(0xFFF4EDFF)
    val TextSecondary = Color(0xFFB9A8D4)
    val TextTertiary = Color(0xFF8B7AA6)
    val Border = Color(0x26FFFFFF)
    val BorderStrong = Color(0x40FFFFFF)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x1FFFFFFF)
    val Danger = Color(0xFFFF7A90)
}

/** Material scheme fed to every screen so Material widgets inherit the night tavern look. */
internal val TavernColorScheme = darkColorScheme(
    primary = TavernPalette.Purple,
    onPrimary = Color.White,
    primaryContainer = TavernPalette.PurpleDeep,
    onPrimaryContainer = TavernPalette.TextPrimary,
    secondary = TavernPalette.PurpleSoft,
    onSecondary = TavernPalette.Night,
    tertiary = TavernPalette.Gold,
    onTertiary = TavernPalette.Night,
    background = TavernPalette.Night,
    onBackground = TavernPalette.TextPrimary,
    surface = TavernPalette.Surface,
    onSurface = TavernPalette.TextPrimary,
    surfaceVariant = TavernPalette.SurfaceRaised,
    onSurfaceVariant = TavernPalette.TextSecondary,
    surfaceContainer = TavernPalette.Surface,
    surfaceContainerHigh = TavernPalette.SurfaceRaised,
    outline = TavernPalette.Border,
    outlineVariant = TavernPalette.BorderStrong,
    error = TavernPalette.Danger,
    onError = Color.White,
)
