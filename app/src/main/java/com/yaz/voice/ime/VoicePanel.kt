package com.yaz.voice.ime

import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaz.voice.feature.home.Phase
import com.yaz.voice.recognize.Problem
import com.yaz.voice.ui.component.BoldButton
import com.yaz.voice.ui.component.BoldIconButton
import com.yaz.voice.ui.component.rememberHaptics
import com.yaz.voice.ui.glass.LocalGlass
import com.yaz.voice.ui.glass.glassGround
import com.yaz.voice.ui.glass.rememberGlassLook
import com.yaz.voice.ui.icon.AppIcons
import com.yaz.voice.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** What the panel's buttons do; the service answers each. */
class PanelKeys(
    val onMic: () -> Unit,
    val onSettings: () -> Unit,
    val onKeyboard: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onBackspace: () -> Unit,
    val onAction: () -> Unit,
    val onReturn: () -> Unit,
    val onClearUnselected: () -> Unit,
    /** The button of a problem: Voice opens, where the microphone or the engine is settled. */
    val onProblem: () -> Unit,
)

/**
 * The voice keyboard's panel: the mic big in the middle, swelling with the voice as HomeScreen's try zone does, round
 * glass keys around it (Transcribro's VoiceInput keys: settings, previous keyboard, undo, redo, backspace, the editor's
 * action, return, "Clear Unselected"), a line for where it stands. An input window has nothing behind it to blur, so the
 * panel paints the ground as AppSurface does (glassGround). Height 220 dp, 560 dp wide at most and centred on a tablet,
 * above the navigation bar. The theme and glass wrapper is AppSurface's (without its activity bars).
 */
@Composable
fun VoicePanel(state: ImeState, keys: PanelKeys) {
    val dark = isSystemInDarkTheme()
    AppTheme(darkTheme = dark) {
        val look = rememberGlassLook(MaterialTheme.colorScheme, true)
        CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassGround(look, MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.TopCenter
            ) {
                // 220 dp is a minimum, not a fixed height: an input window may grow with a longer language or the 200 %
                // font, up to half the screen (heightIn, https://developer.android.com/develop/ui/compose/layouts/size);
                // beyond that the keys scroll up and down inside it, never sideways.
                val most = maxOf(220, LocalConfiguration.current.screenHeightDp / 2).dp
                Box(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = most)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    if (state.password) PasswordPanel(keys) else Keys(state, keys)
                }
            }
        }
    }
}

/** A password field: no dictation, only the way back to the keyboard. */
@Composable
private fun PasswordPanel(keys: PanelKeys) {
    val haptics = rememberHaptics()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxWidth().heightIn(min = 196.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "No dictation in password fields",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Key(AppIcons.Keyboard, "Previous keyboard") { haptics.tick(); keys.onKeyboard() }
    }
}

@Composable
private fun Keys(state: ImeState, keys: PanelKeys) {
    val haptics = rememberHaptics()
    val heard = state.heard
    val listening = heard.phase == Phase.LISTENING

    // A feel for listening starting and ending, and for a failure, as the panel of an app's mic.
    var wasListening by remember { mutableStateOf(listening) }
    LaunchedEffect(listening) {
        if (listening != wasListening) {
            if (listening) haptics.firm() else haptics.done()
            wasListening = listening
        }
    }
    LaunchedEffect(heard.note) { if (heard.note != null) haptics.reject() }

    // Loudness 0 to 1 (Heard.loudness) swells the mic from 1.0 to 1.15, as the try zone's.
    val target = if (listening) 1f + 0.15f * heard.loudness else 1f
    val swell by animateFloatAsState(target, spring(dampingRatio = 0.6f, stiffness = 400f), label = "swell")

    Column(Modifier.fillMaxWidth().heightIn(min = 196.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Key(AppIcons.Settings, "Settings") { haptics.tick(); keys.onSettings() }
            Spacer(Modifier.size(8.dp))
            Key(AppIcons.Keyboard, "Previous keyboard") { haptics.tick(); keys.onKeyboard() }
            // The state between the keys, so the row has no hole (the user's rule: rows fill their width)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                val failed = heard.note != null
                Text(
                    heard.note ?: when (heard.phase) {
                        Phase.LISTENING -> "Listening…"
                        Phase.STOPPING -> "Writing out…"
                        Phase.STARTING -> "Starting…"
                        Phase.IDLE -> "Tap the mic"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    // Wraps between words on as many lines as it needs, never cut.
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Key(AppIcons.DeleteSweep, "Clear unselected text") { haptics.tick(); keys.onClearUnselected() }
            Spacer(Modifier.size(8.dp))
            Key(AppIcons.KeyboardReturn, "Return") { haptics.tick(); keys.onReturn() }
        }

        val problem = state.problem
        if (problem != null && heard.phase == Phase.IDLE) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                BoldButton(onClick = { haptics.tick(); keys.onProblem() }) {
                    Text(if (problem == Problem.MICROPHONE) "Allow the microphone" else "Choose an engine", textAlign = TextAlign.Center)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RepeatKey(AppIcons.Undo, "Undo") { keys.onUndo() }
            RepeatKey(AppIcons.Redo, "Redo") { keys.onRedo() }
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                BoldIconButton(
                    onClick = { haptics.tick(); keys.onMic() },
                    modifier = Modifier.size(72.dp).graphicsLayer { scaleX = swell; scaleY = swell }
                ) {
                    Icon(
                        if (listening) AppIcons.Stop else AppIcons.Mic,
                        contentDescription = if (listening) "Stop listening" else "Start listening",
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            RepeatKey(AppIcons.Backspace, "Backspace") { keys.onBackspace() }
            ActionKey(state.action) { haptics.tick(); keys.onAction() }
        }
    }
}

/** A round glass key of 48 dp. */
@Composable
private fun Key(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    BoldIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

/**
 * The editor's action key, its icon by the action as Transcribro's mapping (next, go, send, done, previous, search; a
 * field with none shows a barred circle and the key is off). A field that leaves the action to the keyboard is Done.
 */
@Composable
private fun ActionKey(action: Int, onClick: () -> Unit) {
    val (icon, description) = when (action) {
        EditorInfo.IME_ACTION_NEXT -> AppIcons.NavigateNext to "Next"
        EditorInfo.IME_ACTION_GO -> AppIcons.ArrowRightAlt to "Go"
        EditorInfo.IME_ACTION_SEND -> AppIcons.Send to "Send"
        EditorInfo.IME_ACTION_PREVIOUS -> AppIcons.NavigateBefore to "Previous"
        EditorInfo.IME_ACTION_SEARCH -> AppIcons.Search to "Search"
        EditorInfo.IME_ACTION_NONE -> AppIcons.Block to "No action"
        else -> AppIcons.Done to "Done"
    }
    Key(icon, description, enabled = action != EditorInfo.IME_ACTION_NONE, onClick = onClick)
}

/**
 * A key that repeats while it is held, as Transcribro's backspace, undo and redo do (its longPressableKey waits for
 * the press, then repeats at the platform's key repeat delay)
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/reusablecomposables/LongPressable.kt
 * with ViewConfiguration.getKeyRepeatTimeout and getKeyRepeatDelay
 * https://developer.android.com/reference/android/view/ViewConfiguration#getKeyRepeatTimeout()
 * The press is watched in the initial pointer pass, so the button's own tap is untouched; a tap that ends a hold that
 * repeated does not act once more.
 */
@Composable
private fun RepeatKey(icon: ImageVector, description: String, onKey: () -> Unit) {
    val haptics = rememberHaptics()
    val act by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf(false) }
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(ViewConfiguration.getKeyRepeatTimeout().toLong())
            repeated = true
            haptics.firm()
            while (isActive) {
                act()
                delay(ViewConfiguration.getKeyRepeatDelay().toLong())
            }
        }
    }
    BoldIconButton(
        onClick = {
            if (!repeated) { haptics.tick(); act() }
            repeated = false
        },
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    repeated = false
                    pressed = true
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    pressed = false
                }
            }
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}
