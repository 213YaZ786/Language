package com.yaz.voice.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Voice's loading mark: five bars of sound that rise and fall in turn, the shape of Material Icons' "graphic_eq"
 * (Apache License 2.0, five rounded bars on the 24 dp grid)
 * https://github.com/google/material-design-icons/blob/master/src/device/graphic_eq/materialicons/24px.svg
 * and, as Calculator's LoadingMark does, a clock from an infinite transition that staggers the pieces, each one
 * moving in its turn
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/ui/component/LoadingMark.kt
 * The bars keep moving while [running] and rest at the icon's heights otherwise. A [progress] between 0 and 1 (a
 * download) is shown by the bars filling from the left, the rest faint.
 */
@Composable
fun LoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    running: Boolean = true,
    progress: Float = 0f
) {
    val tone = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "waves")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "clock"
    )

    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / GRID
        val corner = CornerRadius(BAR_WIDTH * unit / 2f)
        fun bars(alpha: Float) {
            for (i in REST.indices) {
                val height = if (running) {
                    // Each bar's own turn, a little after its neighbour's.
                    val wave = 0.5f + 0.5f * sin(2f * PI.toFloat() * (clock - i * STAGGER))
                    LOW + (PEAK[i] - LOW) * wave
                } else {
                    REST[i]
                }
                drawRoundRect(
                    color = tone.copy(alpha = alpha),
                    topLeft = Offset((CENTERS[i] - BAR_WIDTH / 2f) * unit, (GRID / 2f - height / 2f) * unit),
                    size = Size(BAR_WIDTH * unit, height * unit),
                    cornerRadius = corner
                )
            }
        }
        if (progress in 0.001f..0.999f) {
            bars(0.25f)
            clipRect(right = this.size.width * progress) { bars(1f) }
        } else {
            bars(1f)
        }
    }
}

/** The icon's grid, its bars' centres and width, and their heights at rest (graphic_eq's own). */
private const val GRID = 24f
private const val BAR_WIDTH = 2.4f
private val CENTERS = floatArrayOf(4f, 8f, 12f, 16f, 20f)
private val REST = floatArrayOf(4f, 12f, 20f, 12f, 4f)
private val PEAK = floatArrayOf(10f, 18f, 22f, 18f, 10f)
private const val LOW = 4f
private const val STAGGER = 0.11f
private const val LOOP_MILLIS = 1100
