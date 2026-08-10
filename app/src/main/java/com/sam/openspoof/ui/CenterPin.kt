package com.sam.openspoof.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** How far the pin rises off the map while the map is moving. */
private val LIFT_DISTANCE = 10.dp

private val HEAD_RADIUS = 11.dp
private val STEM_LENGTH = 26.dp

/**
 * The marker at the centre of the viewport, which is the position that will be spoofed.
 *
 * A fixed centre pin is used rather than a tap-to-drop marker because it keeps the target under
 * the user's control at all times without occluding it with a finger, and it means the
 * coordinate readout always describes exactly one point.
 *
 * The pin lifts while the map is in motion and touches down when it settles, so the moment the
 * coordinate stops changing is visible rather than something the user has to infer. The shadow
 * carries that: it shrinks and darkens as the pin drops, the way a real shadow would.
 */
@Composable
fun CenterPin(
    lifted: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    // Spatial spring for the lift: this is movement, so it belongs on the spatial track and is
    // allowed to overshoot slightly as the pin touches down.
    val lift by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "lift",
    )

    // Colour is a non-spatial change, so it uses the effects track, which does not overshoot.
    // An overshooting colour spring would visibly overshoot past the target hue.
    val pinColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "pinColor",
    )

    val onPin = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.surface
    }

    // A slow ring, running only while a position is actually being broadcast, so the map itself
    // shows that spoofing is live without needing a badge.
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseProgress",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val anchor = Offset(size.width / 2f, size.height / 2f)
        val liftPx = LIFT_DISTANCE.toPx() * lift
        val headRadius = HEAD_RADIUS.toPx()
        val stem = STEM_LENGTH.toPx()

        if (active) {
            // Ring expands and fades; drawn under the pin so it reads as ground effect.
            val radius = headRadius + pulse * headRadius * 3.2f
            drawCircle(
                color = pinColor.copy(alpha = (1f - pulse) * 0.45f),
                radius = radius,
                center = anchor,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        // Contact shadow. Tightens as the pin descends, which is what sells the height change.
        val shadowScale = 1f - lift * 0.45f
        drawOval(
            color = Color.Black.copy(alpha = 0.26f * (1f - lift * 0.55f)),
            topLeft = Offset(
                anchor.x - headRadius * 0.62f * shadowScale,
                anchor.y - headRadius * 0.22f * shadowScale,
            ),
            size = Size(
                headRadius * 1.24f * shadowScale,
                headRadius * 0.44f * shadowScale,
            ),
        )

        val headCenter = Offset(anchor.x, anchor.y - stem - liftPx)

        drawLine(
            color = pinColor,
            start = Offset(anchor.x, anchor.y - liftPx * 0.15f),
            end = headCenter,
            strokeWidth = 3.dp.toPx(),
        )
        drawCircle(color = pinColor, radius = headRadius, center = headCenter)
        drawCircle(color = onPin, radius = headRadius * 0.36f, center = headCenter)
    }
}
