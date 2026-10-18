package com.yaz.voice.recognize

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.yaz.voice.feature.home.Heard
import com.yaz.voice.feature.home.Phase
import com.yaz.voice.ui.component.BoldButton
import com.yaz.voice.ui.component.BoldIconButton
import com.yaz.voice.ui.component.ZoneSurface
import com.yaz.voice.ui.icon.AppIcons
import java.util.Locale

/**
 * The panel an app's mic opens (RecognizerIntent.ACTION_RECOGNIZE_SPEECH): a glass sheet at the bottom of a dimmed
 * window, over the app that asked. Content after Google's speech panel (a line to say what to speak, the language,
 * a mic that follows the voice, "Try again") and Apple's dictation (the words appear as they are said, a tap ends),
 * with the mic and the live words of HomeScreen's TryZone (swell by loudness, said words then the ones still coming
 * in a lighter colour, scrolled to the end); the slide and fade of Compose's AnimatedVisibility
 * https://developer.android.com/develop/ui/compose/animation/composables-modifiers#animatedvisibility
 * A tap outside the sheet cancels, as Transcribro's activity closes when left.
 */
@Composable
fun RecognizePanel(
    shown: MutableTransitionState<Boolean>,
    asked: Asked,
    heard: Heard,
    problem: Problem?,
    onMic: () -> Unit,
    onAction: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Cancel", onClick = onCancel),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            // Taps on the sheet itself are not "outside": they stop here.
            Box(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            ) {
                Sheet(asked, heard, problem, onMic, onAction)
            }
        }
    }
}

@Composable
private fun Sheet(asked: Asked, heard: Heard, problem: Problem?, onMic: () -> Unit, onAction: () -> Unit) {
    val listening = heard.phase == Phase.LISTENING
    // Loudness 0 to 1 (Heard.loudness) swells the mic from 1.0 to 1.15, as the try zone's.
    val target = if (listening) 1f + 0.15f * heard.loudness else 1f
    val swell by animateFloatAsState(target, spring(dampingRatio = 0.6f, stiffness = 400f), label = "swell")
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val words = remember(heard.committed, heard.partial, faint) {
        buildAnnotatedString {
            append(heard.committed)
            if (heard.partial.isNotBlank()) {
                if (heard.committed.isNotBlank()) append(" ")
                withStyle(SpanStyle(color = faint)) { append(heard.partial) }
            }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(words) { scroll.animateScrollTo(scroll.maxValue) }
    val line = MaterialTheme.typography.bodyLarge.lineHeight
    val fourLines = with(LocalDensity.current) { line.toDp() * 4 }
    val language = remember(asked.language) {
        asked.language.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    ZoneSurface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            asked.caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ZoneSurface(shape = RoundedCornerShape(50)) {
                Text(
                    language,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                BoldIconButton(
                    onClick = onMic,
                    modifier = Modifier.size(88.dp).graphicsLayer { scaleX = swell; scaleY = swell }
                ) {
                    Icon(
                        if (listening) AppIcons.Stop else AppIcons.Mic,
                        contentDescription = if (listening) "Stop listening" else "Start listening",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            if (heard.text.isNotBlank()) {
                Box(Modifier.fillMaxWidth().heightIn(max = fourLines).verticalScroll(scroll)) {
                    Text(words, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            val failed = heard.note != null
            Text(
                heard.note ?: when (heard.phase) {
                    Phase.LISTENING -> "Listening…"
                    Phase.STOPPING -> "Writing out…"
                    Phase.STARTING -> "Starting…"
                    Phase.IDLE -> "Tap the mic to speak"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (failed && heard.phase == Phase.IDLE) {
                BoldButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (problem) {
                            Problem.MICROPHONE -> "Open settings"
                            Problem.ENGINE -> "Open Voice"
                            null -> "Try again"
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}
