@file:Suppress("MagicNumber", "FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "MatchingDeclarationName")

package com.mistbell.tavern.v3.ui.tavern

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The five states the composer's send button can be in. */
enum class SendButtonState {
    /** Empty draft: the glow breathes. */
    Idle,

    /** Non-empty draft: brighter, the icon lifts by 1dp. */
    Ready,

    /** Waiting for the first token: a 270° arc spins. */
    Sending,

    /** Tokens are streaming in: the plane becomes a pulsing stop square. */
    Streaming,

    /** Cannot send right now. */
    Disabled,
}

/**
 * Send button drawn entirely with the Canvas API, so it needs no Lottie, no sprite sheet and stays
 * vector-sharp on the 3.5x panel.
 *
 * The glow is the reason this is not a plain circle: it breathes while idle (2400 ms), widens when a
 * draft exists, spins with the sending arc and settles into the stop square while tokens stream.
 * Everything is driven by [state], which the caller derives from the real request lifecycle rather
 * than from a timer.
 *
 * Geometry note: the disc is inset by [GLOW_INSET] inside the 56dp box so the outer glow is drawn
 * *within* the button bounds. Drawing it outside would be clipped by the composer row.
 */
@Suppress("LongParameterList")
@Composable
fun TavernSendButton(
    state: SendButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 56.dp,
) {
    val isEnabled = state != SendButtonState.Disabled
    val interactionSource = remember { MutableInteractionSource() }
    val rawPressed by interactionSource.collectIsPressedAsState()
    val isPressed = isEnabled && rawPressed

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec =
            if (isPressed) {
                tween(durationMillis = 90, easing = FastOutSlowInEasing)
            } else {
                spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
            },
        label = "PressScale",
    )

    val readyFactor by animateFloatAsState(
        targetValue = if (state == SendButtonState.Ready) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "ReadyFactor",
    )

    val stateTransition = updateTransition(targetState = state, label = "SendStateTransition")

    val planeAlpha by stateTransition.animateFloat(
        transitionSpec = {
            when {
                targetState == SendButtonState.Sending -> tween(120, easing = FastOutSlowInEasing)
                initialState == SendButtonState.Sending && targetState == SendButtonState.Idle ->
                    tween(200, easing = FastOutSlowInEasing)
                else -> tween(180, easing = FastOutSlowInEasing)
            }
        },
        label = "PlaneAlpha",
    ) { current ->
        if (current == SendButtonState.Idle || current == SendButtonState.Ready) 1f else 0f
    }

    val sendingAlpha by stateTransition.animateFloat(
        transitionSpec = {
            if (initialState == SendButtonState.Sending && targetState == SendButtonState.Idle) {
                tween(200, easing = FastOutSlowInEasing)
            } else {
                tween(150, easing = FastOutSlowInEasing)
            }
        },
        label = "SendingAlpha",
    ) { current ->
        if (current == SendButtonState.Sending) 1f else 0f
    }

    val streamingAlpha by stateTransition.animateFloat(
        transitionSpec = { tween(180, easing = FastOutSlowInEasing) },
        label = "StreamingAlpha",
    ) { current ->
        if (current == SendButtonState.Streaming) 1f else 0f
    }

    val infinite = rememberInfiniteTransition(label = "TavernSendInfinite")

    val idleBreath by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.55f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "IdleBreath",
    )

    val sendingRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "SendingRotation",
    )

    val sendingGlowPulse by infinite.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.65f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "SendingGlowPulse",
    )

    val stopSquarePulse by infinite.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.88f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "StopSquarePulse",
    )

    val ringAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "SciFiRingAngle",
    )

    Box(
        modifier =
            modifier
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    alpha = if (state == SendButtonState.Disabled) 0.40f else 1f
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = isEnabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(buttonSize)) {
            val glowInset = GLOW_INSET.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - glowInset
            val glowRadius = size.minDimension / 2f
            val iconStroke = ICON_STROKE.toPx()
            val iconDiameter = radius * 2f * ICON_FRACTION

            if (state != SendButtonState.Disabled) {
                val baseGlow =
                    when (state) {
                        SendButtonState.Idle -> idleBreath
                        SendButtonState.Ready -> 0.65f
                        SendButtonState.Sending -> sendingGlowPulse
                        SendButtonState.Streaming -> 0.48f
                        SendButtonState.Disabled -> 0f
                    }
                val glowAlpha = if (isPressed) (baseGlow + 0.12f).coerceAtMost(0.80f) else baseGlow
                val stopAtEdge = (radius / glowRadius).coerceIn(0f, 0.99f)
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to TavernPalette.Purple.copy(alpha = glowAlpha),
                                    stopAtEdge to TavernPalette.Purple.copy(alpha = glowAlpha * 0.85f),
                                    1.0f to Color.Transparent,
                                ),
                            center = center,
                            radius = glowRadius,
                        ),
                    radius = glowRadius,
                    center = center,
                )
            }

            drawCircle(
                brush =
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF8B5CF6), Color(0xFF5B21B6)),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius),
                    ),
                radius = radius,
                center = center,
            )

            if (readyFactor > 0f) {
                drawCircle(color = Color.White.copy(alpha = 0.10f * readyFactor), radius = radius, center = center)
            }

            // Glass edge: a short bright arc along the top inner rim.
            drawArc(
                color = Color.White.copy(alpha = if (isPressed) 0.45f else 0.18f),
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - radius + 1.5f, center.y - radius + 1.5f),
                size = Size(radius * 2f - 3f, radius * 2f - 3f),
                style = Stroke(width = 1.5f, cap = StrokeCap.Round),
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.20f),
                radius = radius - 0.5f,
                center = center,
                style = Stroke(width = 1f),
            )

            val showRing =
                state == SendButtonState.Ready ||
                    state == SendButtonState.Sending ||
                    state == SendButtonState.Streaming
            if (showRing) {
                rotate(degrees = ringAngle, pivot = center) {
                    drawCircle(
                        brush =
                            Brush.sweepGradient(
                                0.00f to Color.Transparent,
                                0.65f to Color.Transparent,
                                0.88f to Color.White.copy(alpha = 0.55f),
                                1.00f to Color.Transparent,
                                center = center,
                            ),
                        radius = radius - 1f,
                        center = center,
                        style = Stroke(width = 1.5f),
                    )
                }
            }

            if (planeAlpha > 0.01f) {
                translate(top = -1.dp.toPx() * readyFactor) {
                    drawPaperPlane(
                        center = center,
                        boxSize = iconDiameter,
                        alpha = planeAlpha,
                        strokeWidth = iconStroke,
                    )
                }
            }

            if (sendingAlpha > 0.01f) {
                val arcRadius = iconDiameter * 0.46f
                rotate(degrees = sendingRotation, pivot = center) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.90f * sendingAlpha),
                        startAngle = 0f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                        size = Size(arcRadius * 2f, arcRadius * 2f),
                        style = Stroke(width = iconStroke, cap = StrokeCap.Round),
                    )
                }
            }

            if (streamingAlpha > 0.01f) {
                val side = iconDiameter * 0.46f
                val corner = side * 0.25f
                scale(scale = stopSquarePulse, pivot = center) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = streamingAlpha),
                        topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                        size = Size(side, side),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawPaperPlane(
    center: Offset,
    boxSize: Float,
    alpha: Float,
    strokeWidth: Float,
) {
    val half = boxSize / 2f
    val tip = Offset(center.x + half * 0.92f, center.y - half * 0.92f)
    val leftWing = Offset(center.x - half * 0.92f, center.y - half * 0.14f)
    val rightWing = Offset(center.x + half * 0.14f, center.y + half * 0.92f)
    val fold = Offset(center.x - half * 0.10f, center.y + half * 0.10f)
    val color = Color.White.copy(alpha = alpha)
    val plane =
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(leftWing.x, leftWing.y)
            lineTo(fold.x, fold.y)
            lineTo(rightWing.x, rightWing.y)
            close()
        }
    drawPath(
        path = plane,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawLine(color, tip, fold, strokeWidth, StrokeCap.Round)
}

/** Space reserved between the disc and the button bounds so the glow is not clipped. */
private val GLOW_INSET = 3.dp

/** Icon stroke width at the logical size. */
private val ICON_STROKE = 1.8.dp

/** Icon occupies at most this fraction of the disc diameter. */
private const val ICON_FRACTION = 0.40f
