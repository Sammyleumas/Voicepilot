package com.voicepilot.ai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

enum class MicState { IDLE, LISTENING, BUSY }

/** The large central microphone. One tap to talk, tap again to stop. */
@Composable
fun MicButton(state: MicState, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val ringScale = if (state == MicState.LISTENING) pulse else 1f
    val label = when (state) {
        MicState.IDLE -> "Start listening"
        MicState.LISTENING -> "Stop listening"
        MicState.BUSY -> "Cancel"
    }
    Box(
        modifier = modifier
            .size(size * 1.4f)
            .semantics {
                contentDescription = label
                stateDescription = state.name.lowercase()
            },
        contentAlignment = Alignment.Center,
    ) {
        // Soft outer ring; animates only while listening.
        Box(
            Modifier
                .size(size)
                .scale(ringScale)
                .clip(CircleShape)
                .background(primary.copy(alpha = if (state == MicState.LISTENING) 0.18f else 0.08f)),
        )
        Box(
            Modifier
                .size(size * 0.78f)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(primary, MaterialTheme.colorScheme.secondary)))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .clickable(onClick = onClick, role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                MicState.BUSY -> {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(size * 0.5f))
                    Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                MicState.LISTENING -> Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.3f))
                MicState.IDLE -> Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.32f))
            }
        }
    }
}

/**
 * Audio waveform: bars follow the live input level while listening and settle
 * into a low idle shimmer otherwise.
 */
@Composable
fun Waveform(level: Float, active: Boolean, modifier: Modifier = Modifier, bars: Int = 28) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val idle = MaterialTheme.colorScheme.outline
    val animatedLevel by animateFloatAsState(if (active) level.coerceIn(0.05f, 1f) else 0.06f, tween(120), label = "level")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase",
    )
    val weights = remember(bars) { List(bars) { i -> 0.35f + 0.65f * abs(sin(i * 0.7f)) } }
    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        val gap = 4.dp.toPx()
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val centerY = size.height / 2
        for (i in 0 until bars) {
            val wobble = 0.55f + 0.45f * sin(phase + i * 0.45f)
            val h = (size.height * animatedLevel * weights[i] * wobble).coerceAtLeast(3.dp.toPx())
            val x = i * (barWidth + gap)
            val t = i / (bars - 1f)
            val color = if (active) lerpColor(primary, secondary, t) else idle
            drawRoundRect(color, Offset(x, centerY - h / 2), Size(barWidth, h), CornerRadius(barWidth / 2))
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
