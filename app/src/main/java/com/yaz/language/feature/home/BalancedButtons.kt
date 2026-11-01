package com.yaz.language.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One button of [BalancedButtons]: its label, measured, and how to draw it with the modifier the row gives. [extra] is
 * the width the button holds besides its label (an icon and its gap), so the label keeps its room.
 */
internal class ButtonSpec(val label: String, val extra: Dp = 0.dp, val content: @Composable (Modifier) -> Unit)

/**
 * Buttons across the width: all in one row while each label fits on one line, else balanced rows (3 buttons make 2 + 1,
 * 4 make 2 + 2), never a label cut. The width of a button is measured, not guessed: its widest label on one line in the
 * current style, font scale and language (TextMeasurer, https://developer.android.com/develop/ui/compose/text/measure-text-layout)
 * plus the button's 24 dp each side; the rows are FlowRow with maxItemsInEachRow, every button weight(1f).fillMaxRowHeight()
 * (https://developer.android.com/develop/ui/compose/layouts/flow); the balancing is SMS's PersonActions.perRowFor
 * (https://github.com/213YaZ786/SMS, ui/component/PersonActions.kt).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BalancedButtons(buttons: List<ButtonSpec>, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(buttons.map { it.label to it.extra }, style, density) {
        with(density) { buttons.maxOf { measurer.measure(it.label, style, softWrap = false, maxLines = 1).size.width.toDp() + it.extra } }
    }
    val extra = buttons.maxOf { it.extra }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        val gap = 10.dp
        val tile = widest + 48.dp
        val fit = ((available + gap) / (tile + gap)).toInt().coerceAtLeast(1)
        val rows = (buttons.size + fit - 1) / fit
        val perRow = (buttons.size + rows - 1) / rows
        // One size for every label of the group, from the width a button gets in a full row less its 24 dp each side.
        val inRow = minOf(perRow, buttons.size)
        val buttonWidth = maxOf(minOf(tile, available), (available - gap * (inRow - 1)) / inRow)
        val labelSize = rememberGroupFontSize(buttons.map { it.label }, style, buttonWidth - 48.dp - extra)
        CompositionLocalProvider(LocalGroupFontSize provides labelSize) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                maxItemsInEachRow = perRow
            ) {
                buttons.forEach { it.content(Modifier.weight(1f).fillMaxRowHeight().widthIn(min = minOf(tile, available))) }
            }
        }
    }
}

/** The size [BalancedButtons] computed for its group; unspecified outside one. */
internal val LocalGroupFontSize = compositionLocalOf { TextUnit.Unspecified }

/** The label of a button of a [BalancedButtons] group: wraps between words, at the group's one size. */
@Composable
internal fun GroupLabel(text: String) {
    Text(text, textAlign = TextAlign.Center, fontSize = LocalGroupFontSize.current)
}

/**
 * One text size for all the labels of a group, computed once: the largest size from the style's down to [min], in
 * 0.5 sp steps, at which the longest word of every label fits [textWidth]; the labels are then drawn at that size,
 * wrapping between words, so no two read in two sizes (Material's type scale; Apple's Dynamic Type). The ladder is
 * Compose's TextAutoSize.StepBased, asked of the whole group at once
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/AutoSize.kt),
 * the fit measured with TextMeasurer (https://developer.android.com/develop/ui/compose/text/measure-text-layout) as
 * SMS's rememberGroupLabelSize does (https://github.com/213YaZ786/SMS, ui/component/GroupLabelSize.kt).
 */
@Composable
private fun rememberGroupFontSize(labels: List<String>, style: TextStyle, textWidth: Dp, min: TextUnit = 9.sp): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, style, textWidth, min, density) {
        val max = style.fontSize
        val words = labels.flatMap { it.split(' ', '\n') }.filter { it.isNotEmpty() }.distinct()
        if (!max.isSp || max.value <= min.value || words.isEmpty() || textWidth.value <= 0f) return@remember max
        val limit = with(density) { textWidth.toPx() }
        var size = max.value
        while (size > min.value) {
            val sized = style.copy(fontSize = size.sp)
            if (words.all { measurer.measure(it, sized, softWrap = false, maxLines = 1).size.width <= limit }) return@remember size.sp
            size -= 0.5f
        }
        min
    }
}
