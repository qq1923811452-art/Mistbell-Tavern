// Bubble colours and spacing are visual tokens; every literal below is named so the composables read declaratively.
@file:Suppress("MagicNumber")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the conversation as tavern-style chat bubbles without changing the message rules.
 *
 * User turns align right with a compact avatar; assistant turns align left with a circular initial
 * avatar, the speaker name, a timestamp and the same variant/regenerate actions as before.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ColumnScope.ChatHistoryMessageList(
    history: List<ChatLine>,
    busy: Boolean,
    listState: LazyListState,
    onSelectVariant: (messageId: String, index: Int) -> Unit = { _, _ -> },
    onRegenerate: (messageId: String) -> Unit = {},
) {
    val lastAssistantId = history.lastOrNull { it.role == ROLE_ASSISTANT }?.id
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(MESSAGE_SPACING),
    ) {
        itemsIndexed(history) { _, line ->
            ChatMessageRow(
                line = line,
                busy = busy,
                showRegenerate = !busy && line.role == ROLE_ASSISTANT && line.id == lastAssistantId,
                onSelectVariant = onSelectVariant,
                onRegenerate = onRegenerate,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ChatMessageRow(
    line: ChatLine,
    busy: Boolean,
    showRegenerate: Boolean,
    onSelectVariant: (messageId: String, index: Int) -> Unit,
    onRegenerate: (messageId: String) -> Unit,
) {
    val isUser = line.role == ROLE_USER
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * BUBBLE_MAX_WIDTH_FRACTION
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!isUser) {
                MessageAvatar(name = speakerLabel(line), isUser = false)
                Spacer(Modifier.width(AVATAR_SPACING))
            }
            Column(
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                MessageHeader(line = line, isUser = isUser)
                Spacer(Modifier.height(HEADER_GAP))
                ChatBubble(
                    line = line,
                    busy = busy,
                    showRegenerate = showRegenerate,
                    onSelectVariant = onSelectVariant,
                    onRegenerate = onRegenerate,
                )
            }
            if (isUser) {
                Spacer(Modifier.width(AVATAR_SPACING))
                MessageAvatar(name = USER_DISPLAY_NAME, isUser = true)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MessageHeader(line: ChatLine, isUser: Boolean) {
    val time = rememberMessageTime(line.createdAtEpochMillis)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NAME_TIME_SPACING),
    ) {
        if (!isUser) {
            Text(
                text = speakerLabel(line),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = SPEAKER_NAME_COLOR,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (time.isNotEmpty()) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = TIME_TEXT_COLOR,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ChatBubble(
    line: ChatLine,
    busy: Boolean,
    showRegenerate: Boolean,
    onSelectVariant: (messageId: String, index: Int) -> Unit,
    onRegenerate: (messageId: String) -> Unit,
) {
    val isUser = line.role == ROLE_USER
    val shape = bubbleShape(isUser)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(if (isUser) USER_BUBBLE_COLOR else ASSISTANT_BUBBLE_COLOR)
            .border(
                width = BUBBLE_BORDER_WIDTH,
                color = if (isUser) USER_BORDER_COLOR else ASSISTANT_BORDER_COLOR,
                shape = shape,
            )
            .padding(BUBBLE_PADDING),
    ) {
        Text(
            text = messageText(line = line, busy = busy),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
            color = BUBBLE_TEXT_COLOR,
        )
        MessageActions(
            line = line,
            showRegenerate = showRegenerate,
            onSelectVariant = onSelectVariant,
            onRegenerate = onRegenerate,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MessageActions(
    line: ChatLine,
    showRegenerate: Boolean,
    onSelectVariant: (messageId: String, index: Int) -> Unit,
    onRegenerate: (messageId: String) -> Unit,
) {
    if (showRegenerate) {
        TextButton(
            onClick = { onRegenerate(line.id) },
            contentPadding = PaddingValues(horizontal = ACTION_HORIZONTAL_PADDING),
            colors = ButtonDefaults.textButtonColors(contentColor = ACTION_TEXT_COLOR),
        ) {
            Text(text = "重新生成", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (line.variants.size > 1) {
        SwipeControls(
            index = line.activeVariant,
            total = line.variants.size,
            onPrevious = { onSelectVariant(line.id, line.activeVariant - 1) },
            onNext = { onSelectVariant(line.id, line.activeVariant + 1) },
            modifier = Modifier
                .padding(top = VARIANT_TOP_SPACING)
                .clip(RoundedCornerShape(VARIANT_RADIUS))
                .background(VARIANT_SURFACE_COLOR)
                .padding(horizontal = VARIANT_HORIZONTAL_PADDING),
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MessageAvatar(name: String, isUser: Boolean) {
    Box(
        modifier = Modifier
            .size(if (isUser) USER_AVATAR_SIZE else ASSISTANT_AVATAR_SIZE)
            .clip(CircleShape)
            .background(if (isUser) USER_AVATAR_COLOR else ASSISTANT_AVATAR_COLOR),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.toString() ?: AVATAR_FALLBACK_INITIAL,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isUser) USER_AVATAR_TEXT_COLOR else ASSISTANT_AVATAR_TEXT_COLOR,
        )
    }
}

@Composable
private fun rememberMessageTime(epochMillis: Long): String = remember(epochMillis) {
    if (epochMillis <= 0L) {
        ""
    } else {
        SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(epochMillis))
    }
}

private fun speakerLabel(line: ChatLine): String {
    if (line.role == ROLE_USER) {
        return USER_DISPLAY_NAME
    }
    return line.speakerName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SPEAKER_NAME
}

private fun messageText(line: ChatLine, busy: Boolean): String =
    line.activeContent.ifBlank {
        when {
            busy && line.role == ROLE_ASSISTANT -> "正在生成……"
            line.deliveryState == PersistedChatMessage.DeliveryState.PENDING ->
                "上次回复未完成，可重新发送。"
            line.deliveryState == PersistedChatMessage.DeliveryState.FAILED &&
                line.role == ROLE_ASSISTANT ->
                "上次回复未完成，未生成完整回答。"
            else -> ""
        }
    }

private fun bubbleShape(isUser: Boolean): RoundedCornerShape =
    if (isUser) {
        RoundedCornerShape(
            topStart = BUBBLE_RADIUS,
            topEnd = BUBBLE_TAIL_RADIUS,
            bottomEnd = BUBBLE_RADIUS,
            bottomStart = BUBBLE_RADIUS,
        )
    } else {
        RoundedCornerShape(
            topStart = BUBBLE_TAIL_RADIUS,
            topEnd = BUBBLE_RADIUS,
            bottomEnd = BUBBLE_RADIUS,
            bottomStart = BUBBLE_RADIUS,
        )
    }

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val USER_DISPLAY_NAME = "你"
private const val DEFAULT_SPEAKER_NAME = "Mistbell"
private const val AVATAR_FALLBACK_INITIAL = "?"
private const val TIME_PATTERN = "HH:mm"

private val MESSAGE_SPACING = 10.dp
private val HEADER_GAP = 4.dp
private val NAME_TIME_SPACING = 8.dp
private val AVATAR_SPACING = 8.dp
private val ASSISTANT_AVATAR_SIZE = 40.dp
private val USER_AVATAR_SIZE = 30.dp
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.86f
private val BUBBLE_RADIUS = 18.dp
private val BUBBLE_TAIL_RADIUS = 4.dp
private val BUBBLE_BORDER_WIDTH = 1.dp
private val BUBBLE_PADDING = 12.dp
private val ACTION_HORIZONTAL_PADDING = 8.dp
private val VARIANT_TOP_SPACING = 4.dp
private val VARIANT_RADIUS = 12.dp
private val VARIANT_HORIZONTAL_PADDING = 4.dp

private val ASSISTANT_BUBBLE_COLOR = Color(0xE6342A3E)
private val ASSISTANT_BORDER_COLOR = Color(0x668F7AB5)
private val USER_BUBBLE_COLOR = Color(0xE64B3527)
private val USER_BORDER_COLOR = Color(0x66D9A66C)
private val BUBBLE_TEXT_COLOR = Color(0xFFF7F0E6)
private val SPEAKER_NAME_COLOR = Color(0xFFE9C98F)
private val TIME_TEXT_COLOR = Color(0xB3F7F0E6)
private val ACTION_TEXT_COLOR = Color(0xFFE9C98F)
private val ASSISTANT_AVATAR_COLOR = Color(0xFF57406B)
private val ASSISTANT_AVATAR_TEXT_COLOR = Color(0xFFF4E9FF)
private val USER_AVATAR_COLOR = Color(0xFF7A5A3C)
private val USER_AVATAR_TEXT_COLOR = Color(0xFFFFF2E0)
private val VARIANT_SURFACE_COLOR = Color(0xE6EFE6D8)
