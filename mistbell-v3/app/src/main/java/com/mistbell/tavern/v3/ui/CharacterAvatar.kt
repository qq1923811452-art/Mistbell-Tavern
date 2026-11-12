@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.v3.feature.character.CharacterArtMemory
import com.mistbell.tavern.v3.feature.character.decodeCharacterArt
import com.mistbell.tavern.v3.ui.tavern.TavernPalette

private val AVATAR_PLACEHOLDER_BRUSH =
    Brush.linearGradient(
        listOf(Color(0xFF7C3AED), Color(0xFFC084FC)),
    )
private val RING_BRUSH =
    Brush.linearGradient(
        listOf(TavernPalette.Purple, TavernPalette.Gold),
    )
private val PORTRAIT_PLACEHOLDER_BRUSH =
    Brush.verticalGradient(
        listOf(Color(0xFF2A1B3D), Color(0xFF120A1B)),
    )

/**
 * Circular character picture with a gradient-ring frame and an initial fallback.
 *
 * Artwork is loaded through [CharacterArtMemory] so scrolling a session list never touches the disk
 * twice for the same file, and the bitmap is memoised per file name rather than per recomposition.
 */
@Suppress("FunctionNaming")
@Composable
fun CharacterAvatar(
    name: String,
    avatarFileName: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Boolean = true,
) {
    val context = LocalContext.current
    val bytes = remember(avatarFileName) { CharacterArtMemory.getOrLoad(context.filesDir, avatarFileName) }
    val bitmap = remember(bytes) { decodeCharacterArt(bytes)?.asImageBitmap() }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(AVATAR_PLACEHOLDER_BRUSH)
                .then(if (ring) Modifier.border(1.5.dp, RING_BRUSH, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.toString() ?: "?",
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
    }
}

/**
 * Standing portrait (立绘) used by the character picker and the session settings sheet.
 *
 * Falls back to a vertical night gradient with the character initial so a card without art still
 * looks deliberate instead of broken.
 */
@Suppress("FunctionNaming")
@Composable
fun CharacterPortrait(
    name: String,
    portraitFileName: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
) {
    val context = LocalContext.current
    val bytes = remember(portraitFileName) { CharacterArtMemory.getOrLoad(context.filesDir, portraitFileName) }
    val bitmap = remember(bytes) { decodeCharacterArt(bytes)?.asImageBitmap() }
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier =
            modifier
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(PORTRAIT_PLACEHOLDER_BRUSH)
                .border(1.dp, TavernPalette.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                Text(
                    text = name.trim().firstOrNull()?.toString() ?: "?",
                    color = TavernPalette.PurpleSoft.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 56.sp,
                )
                Text(
                    text = "尚未设置立绘",
                    color = TavernPalette.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
