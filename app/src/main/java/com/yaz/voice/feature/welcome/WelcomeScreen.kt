package com.yaz.voice.feature.welcome

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.core.update.Updates
import com.yaz.voice.core.voice.SpeechModel
import com.yaz.voice.feature.common.isPhoneVoiceInput
import com.yaz.voice.feature.common.isVoiceKeyboardEnabled
import com.yaz.voice.feature.common.openKeyboardSettings
import com.yaz.voice.feature.common.openVoiceInputSettings
import com.yaz.voice.feature.common.rememberMicrophone
import com.yaz.voice.ui.component.BoldButton
import com.yaz.voice.ui.component.LoadingMark
import com.yaz.voice.ui.component.QuietButton
import com.yaz.voice.ui.component.ZoneSurface
import com.yaz.voice.ui.component.rememberHaptics
import com.yaz.voice.ui.icon.AppIcons
import org.koin.compose.koinInject

/**
 * The one page shown at the first launch: the few choices worth making, the best one already set; each zone turns
 * into a tick once done. Copied from Calculator's WelcomeScreen
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/main/WelcomeScreen.kt
 * with SMS's speech step (the model for this phone chosen, fetched on a tap, its progress shown)
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/main/WelcomeScreen.kt
 * Steps: the microphone, the engine, Voice as the phone's voice input, the voice keyboard (optional), automatic updates.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val speech: VoiceSpeech = koinInject()
    val model = speech.model
    val haptics = rememberHaptics()
    val mic = rememberMicrophone()
    val state by model.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val system by model.system.collectAsState()
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        model.recheck()
        onPauseOrDispose { }
    }
    val metered = remember { model.metered() }
    val voiceInput = remember(checks) { isPhoneVoiceInput(context) }
    val voiceKeyboard = remember(checks) { isVoiceKeyboardEnabled(context) }
    val canInstall = remember(checks) { Updates.canInstall(context) }
    val notifying = remember(checks) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Spacer(Modifier.height(40.dp))
        LoadingMark(size = 140.dp, running = false)
        Spacer(Modifier.height(20.dp))
        Text("Welcome to Voice", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        WelcomeZone(AppIcons.Mic, "Microphone", done = mic.granted) {
            if (!mic.granted) BoldButton(onClick = { haptics.tick(); mic.request() }) {
                Text(if (mic.refusedForGood) "Open settings" else "Allow the microphone")
            }
        }
        Spacer(Modifier.height(16.dp))
        // The phone's recognizer when it has the language, else the model for this phone, already chosen.
        // Parts joined with a comma, as AOSP Dialer's CallLogEntryText.java (https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/java/com/android/dialer/calllogutils/CallLogEntryText.java).
        val name = if (model.usesSystem) "This phone's recognizer" else "Whisper ${model.pin.label}, ${model.pin.bytes / 1_000_000} MB"
        WelcomeZone(AppIcons.Memory, "Engine", done = state == SpeechModel.State.Ready) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            when (val s = state) {
                is SpeechModel.State.Fetching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingMark(size = 28.dp, progress = s.done)
                    Text("${(s.done * 100).toInt()}%", modifier = Modifier.weight(1f))
                    QuietButton(onClick = { haptics.tick(); model.cancel() }) { Text("Stop") }
                }
                SpeechModel.State.Missing, SpeechModel.State.Failed -> {
                    if (s == SpeechModel.State.Failed || metered) {
                        Text(
                            if (s == SpeechModel.State.Failed) "Could not get it" else "On mobile data",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    BoldButton(onClick = { haptics.done(); model.fetch() }) {
                        Text(if (s == SpeechModel.State.Failed) "Retry" else "Download")
                    }
                }
                SpeechModel.State.Ready -> Unit
            }
        }
        Spacer(Modifier.height(16.dp))
        WelcomeZone(AppIcons.Keyboard, "Voice input", done = voiceInput) {
            if (!voiceInput) BoldButton(onClick = { haptics.tick(); openVoiceInputSettings(context) }) { Text("Choose Voice") }
        }
        Spacer(Modifier.height(16.dp))
        WelcomeZone(AppIcons.Keyboard, "Voice keyboard (optional)", done = voiceKeyboard) {
            if (!voiceKeyboard) BoldButton(onClick = { haptics.tick(); openKeyboardSettings(context) }) { Text("Activate") }
        }
        Spacer(Modifier.height(16.dp))
        WelcomeZone(AppIcons.Update, "Automatic updates", done = canInstall && notifying) {
            if (!notifying) BoldButton(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            else if (!canInstall) BoldButton(onClick = { Updates.allowInstalls(context) }) { Text("Allow updates") }
        }

        Spacer(Modifier.height(32.dp))
        BoldButton(filled = true, onClick = onStart) { Text("Start") }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** One thing to settle, a tick once done. Copied from Calculator's WelcomeZone (same file as WelcomeScreen). */
@Composable
private fun WelcomeZone(icon: ImageVector, title: String, done: Boolean, content: @Composable () -> Unit) {
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (done) AppIcons.CheckCircle else icon, contentDescription = if (done) "Done" else null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            content()
        }
    }
}
