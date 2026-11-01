package com.yaz.language.ui.component

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yaz.language.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Language's loading mark, the launcher icon in motion (icon B: Material Symbols "translate" in ivory relief on the YAZ
 * disc). Ported from Dialer's LoadingMark
 * https://github.com/213YaZ786/Dialer/blob/main/app/src/main/java/com/yaz/dialer/ui/component/LoadingMark.kt
 * and Modules' icons/README.md "Raster recipe": the grey body multiplied by the launcher's disc tone with
 * ColorFilter.tint(..., BlendMode.Modulate) (by night the theme's inversePrimary, primary being pale then), its white
 * rim light over it, the sign on top. At rest the three layers are the icon.
 *
 * While [running] the disc breathes and the sign rocks a little, as Dialer's disc and handset do. A [progress]
 * between 0 and 1 (a download) shows the mark filling from the left, the rest faint (this app's own meaning of
 * [progress], kept from its former bars).
 */
@Composable
fun LoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    running: Boolean = true,
    progress: Float = 0f
) {
    val body = ImageBitmap.imageResource(R.drawable.language_mark_body)
    val shine = ImageBitmap.imageResource(R.drawable.language_mark_shine)
    val sign = ImageBitmap.imageResource(R.drawable.language_mark_sign)
    // The launcher icon's own tone (ic_launcher_disc) by day, inversePrimary by night.
    val scheme = MaterialTheme.colorScheme
    val disc = colorResource(R.color.ic_launcher_disc)
    val tone = if (scheme.background.luminance() < 0.5f) scheme.inversePrimary else disc
    val tint = remember(tone) { ColorFilter.tint(tone, BlendMode.Modulate) }

    val transition = rememberInfiniteTransition(label = "translating")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "clock"
    )
    val t = if (running) clock else 0f

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * DISC
        // Dialer's motion: the disc breathes and the sign rocks; still at rest.
        val breath = if (running) 1f + 0.035f * sin(2f * PI.toFloat() * t * 2f) else 1f
        val rock = if (running) 4f * sin(2f * PI.toFloat() * t * 4f) else 0f
        fun mark(alpha: Float) {
            scale(breath) {
                layer(body, r, tint, alpha)
                layer(shine, r, null, alpha)
                rotate(rock) { layer(sign, r, null, alpha) }
            }
        }
        if (progress in 0.001f..0.999f) {
            mark(0.25f)
            val left = center.x - r
            clipRect(left = left, right = left + 2f * r * progress) { mark(1f) }
        } else {
            mark(1f)
        }
    }
}

// Dialer's layer(): one bitmap drawn square and centred at the disc's size.
private fun DrawScope.layer(image: ImageBitmap, r: Float, filter: ColorFilter?, alpha: Float) {
    val side = (r * 2f).toInt()
    drawImage(
        image,
        dstOffset = IntOffset((center.x - r).toInt(), (center.y - r).toInt()),
        dstSize = IntSize(side, side),
        alpha = alpha,
        colorFilter = filter,
        filterQuality = FilterQuality.High
    )
}

/** The disc's share of the mark. */
private const val DISC = 0.70f
private const val LOOP_MILLIS = 2400
