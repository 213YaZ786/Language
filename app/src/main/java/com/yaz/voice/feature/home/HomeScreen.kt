package com.yaz.voice.feature.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.voice.BuildConfig
import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.core.voice.SpeechModel
import com.yaz.voice.data.settings.SettingsStore
import com.yaz.voice.feature.common.isPhoneVoiceInput
import com.yaz.voice.feature.common.openVoiceInputSettings
import com.yaz.voice.feature.common.rememberMicrophone
import com.yaz.voice.navigation.LocalReadableInset
import com.yaz.voice.ui.component.BoldButton
import com.yaz.voice.ui.component.BoldIconButton
import com.yaz.voice.ui.component.FloatingAction
import com.yaz.voice.ui.component.FloatingFrame
import com.yaz.voice.ui.component.FloatingTop
import com.yaz.voice.ui.component.LoadingMark
import com.yaz.voice.ui.component.UpdatePrompt
import com.yaz.voice.ui.component.ZoneSurface
import com.yaz.voice.ui.component.rememberHaptics
import com.yaz.voice.ui.icon.AppIcons
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Home: where Voice stands (microphone, engine, the phone's voice input), then a place to try it. Built on
 * Calculator's glass screens (FloatingFrame under a title pill, ZoneSurface zones, ListItem rows as its
 * SettingsScreen's SettingRow) https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt
 * with the status rows of Transcribro's StartScreen
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/start/StartScreen.kt
 * (each requirement ticked or with its action).
 */
@Composable
fun HomeScreen(onSettings: () -> Unit, viewModel: HomeViewModel = koinViewModel()) {
    val context = LocalContext.current
    val speech: VoiceSpeech = koinInject()
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val haptics = rememberHaptics()
    val heard by viewModel.heard.collectAsState()
    val mic = rememberMicrophone()
    val model = speech.model
    val state by model.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val system by model.system.collectAsState()

    // The rows are read again each time the screen comes back (from Android's Settings, say).
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        model.recheck()
        onPauseOrDispose { }
    }
    val voiceInput = remember(checks) { isPhoneVoiceInput(context) }

    // Leaving the screen ends listening and drops what is held.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stop(false) }
    DisposableEffect(Unit) { onDispose { viewModel.stop(false) } }

    // A feel for listening starting and ending, and for a failure.
    val listening = heard.phase == Phase.LISTENING
    var wasListening by remember { mutableStateOf(listening) }
    LaunchedEffect(listening) {
        if (listening != wasListening) {
            if (listening) haptics.firm() else haptics.done()
            wasListening = listening
        }
    }
    LaunchedEffect(heard.note) { if (heard.note != null) haptics.reject() }

    val onMic = {
        when {
            heard.phase == Phase.LISTENING -> viewModel.stop(true)
            heard.phase != Phase.IDLE -> Unit
            !mic.granted -> mic.request()
            else -> viewModel.start(noEngine = onSettings)
        }
    }

    FloatingFrame(
        bottom = 0.dp,
        top = { FloatingTop("Voice", trailing = { FloatingAction(AppIcons.Settings, "Settings", onSettings) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalReadableInset.current + 16.dp)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            Spacer(Modifier.height(8.dp))

            ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    StatusRow(
                        icon = AppIcons.Mic,
                        title = "Microphone",
                        summary = if (mic.granted) "Allowed" else "Not allowed",
                        ok = mic.granted,
                        action = if (mic.refusedForGood) "Settings" else "Allow",
                        onClick = { haptics.tick(); mic.request() }
                    )
                    EngineRow(model, state, networkAllowed = settings.networkAllowed, onSettings = onSettings)
                    StatusRow(
                        icon = AppIcons.Keyboard,
                        title = "Voice input",
                        summary = if (voiceInput) "Voice is the phone's voice input" else "Not the phone's voice input",
                        ok = voiceInput,
                        action = "Choose",
                        onClick = { haptics.tick(); openVoiceInputSettings(context) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            TryZone(
                heard = heard,
                onMic = { haptics.tick(); onMic() },
                onCopy = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Voice", heard.text))
                    haptics.done()
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, heard.text)
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onClear = { haptics.tick(); viewModel.clear() }
            )

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    UpdatePrompt(settings.updates, BuildConfig.VERSION_NAME)
}

/**
 * The engine row: its name and size, and where it stands (Missing, Fetching with its percent, Ready, Failed with
 * Retry), after SMS's speech model panel (core voice SpeechModelPanel in feature/thread/Voice.kt,
 * https://github.com/213YaZ786/SMS) and Calculator's SettingRow.
 */
@Composable
private fun EngineRow(model: SpeechModel, state: SpeechModel.State, networkAllowed: Boolean, onSettings: () -> Unit) {
    val haptics = rememberHaptics()
    val name = if (model.usesSystem) "This phone's recognizer" else "Whisper ${model.pin.label} · ${model.pin.bytes / 1_000_000} MB"
    val get = {
        haptics.done()
        if (networkAllowed) model.fetch() else onSettings()
    }
    when (state) {
        SpeechModel.State.Ready -> StatusRow(AppIcons.Memory, "Engine", name, ok = true, action = null, onClick = { haptics.tick(); onSettings() })
        SpeechModel.State.Missing -> StatusRow(
            AppIcons.Memory, "Engine", if (networkAllowed) name else "$name · the network is off",
            ok = false, action = if (networkAllowed) "Get" else "Settings", onClick = get
        )
        is SpeechModel.State.Fetching -> StatusRow(
            AppIcons.Memory, "Engine", "$name · ${(state.done * 100).toInt()}%",
            ok = false, action = "Stop", onClick = { haptics.tick(); model.cancel() }, progress = state.done
        )
        SpeechModel.State.Failed -> StatusRow(
            AppIcons.Memory, "Engine", "$name · could not get it",
            ok = false, problem = true, action = "Retry", onClick = get
        )
    }
}

/**
 * One requirement: ticked when done, else its icon and an action; the whole row is the target. A list item on a
 * transparent surface as Calculator's SettingRow, since a tap target inside a zone is never a second ZoneSurface.
 * A [progress] shows the loading mark in the place of the icon.
 */
@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    summary: String,
    ok: Boolean,
    action: String?,
    onClick: () -> Unit,
    problem: Boolean = false,
    progress: Float? = null
) {
    ListItem(
        leadingContent = {
            if (progress != null) LoadingMark(size = 24.dp, progress = progress)
            else Icon(
                if (ok) AppIcons.CheckCircle else icon,
                contentDescription = if (ok) "Done" else null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = {
            Text(summary, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = if (!ok && action != null) ({ BoldButton(onClick = onClick) { Text(action, maxLines = 1) } }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    )
}

/**
 * The place to try it: a big round glass mic that swells gently with the voice, the words (said ones, then the ones
 * still coming in a lighter colour), Copy, Share and Clear across the width.
 */
@Composable
private fun TryZone(heard: Heard, onMic: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onClear: () -> Unit) {
    val listening = heard.phase == Phase.LISTENING
    // Loudness 0 to 10 dB (Android's onRmsChanged range) swells the mic from 1.0 to 1.15.
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
    val empty = heard.text.isBlank()

    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                BoldIconButton(
                    onClick = onMic,
                    modifier = Modifier.size(104.dp).graphicsLayer { scaleX = swell; scaleY = swell }
                ) {
                    Icon(
                        if (listening) AppIcons.Stop else AppIcons.Mic,
                        contentDescription = if (listening) "Stop listening" else "Start listening",
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            heard.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp).verticalScroll(scroll),
                contentAlignment = if (empty) Alignment.Center else Alignment.TopStart
            ) {
                if (empty) {
                    val hint = when (heard.phase) {
                        Phase.LISTENING -> "Listening…"
                        Phase.STOPPING -> "Writing out…"
                        Phase.STARTING -> "Starting…"
                        Phase.IDLE -> "Tap the mic and speak"
                    }
                    Text(hint, color = faint, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(words, style = MaterialTheme.typography.bodyLarge, overflow = TextOverflow.Clip)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                BoldButton(onClick = onCopy, enabled = !empty, modifier = Modifier.weight(1f)) { Text("Copy", maxLines = 1) }
                BoldButton(onClick = onShare, enabled = !empty, modifier = Modifier.weight(1f)) { Text("Share", maxLines = 1) }
                BoldButton(onClick = onClear, enabled = !empty, modifier = Modifier.weight(1f)) { Text("Clear", maxLines = 1) }
            }
        }
    }
}
