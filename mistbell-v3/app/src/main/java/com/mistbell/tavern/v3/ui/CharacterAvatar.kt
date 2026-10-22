package com.mistbell.tavern.v3.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp

/**
 * Circular character picture with an initial fallback. Bytes are decoded once per change so
 * scrolling a session list does not re-decode bitmaps on every frame.
 */
@Suppress("FunctionNaming")
@Composable
fun CharacterAvatar(
    name: String,
    bytes: ByteArray?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(bytes) {
        bytes?.let { data -> BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF2A2140)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
