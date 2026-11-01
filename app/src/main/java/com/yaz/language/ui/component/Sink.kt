package com.yaz.language.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Sinks a little under the finger and springs back on release, for any pill, card or round action. It only
 * watches the touch, so the element's own click still answers. Copied from Calculator's ui/component/Sink.kt
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/ui/component/Sink.kt
 */
fun Modifier.sink(depth: Float = 0.92f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) depth else 1f, spring(dampingRatio = 0.45f, stiffness = 800f), label = "sink")
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
            }
        }
}
