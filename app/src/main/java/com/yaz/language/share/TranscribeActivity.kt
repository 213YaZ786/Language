package com.yaz.language.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextAlign
import com.yaz.language.feature.home.BalancedButtons
import com.yaz.language.feature.home.GroupLabel
import com.yaz.language.feature.home.ButtonSpec
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.yaz.language.MainActivity
import com.yaz.language.ui.component.BoldButton
import com.yaz.language.ui.component.FloatingAction
import com.yaz.language.ui.component.FloatingFrame
import com.yaz.language.ui.component.FloatingTop
import com.yaz.language.ui.component.LoadingMark
import com.yaz.language.ui.component.ZoneSurface
import com.yaz.language.ui.component.rememberHaptics
import com.yaz.language.ui.icon.AppIcons
import com.yaz.language.ui.theme.AppSurface
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * A sound shared to Language (ACTION_SEND of audio): its words, to copy or share. Only another app's content:// is
 * read, never a file:// nor one of Language's own (developer.android.com/privacy-and-security/risks/content-resolver,
 * https://developer.android.com/privacy-and-security/risks/content-resolver); the intent only names the sound.
 * Built as Home's try zone (FloatingFrame under a title pill, a ZoneSurface, BoldButtons), Calculator's glass screens
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt
 */
class TranscribeActivity : ComponentActivity() {

    private val model: TranscribeViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.takeIf { foreign(it) }
        if (uri == null) {
            finish()
            return
        }
        model.start(uri)
        setContent { AppSurface { TranscriptScreen(model, onClose = ::finish, onEngine = ::openVoice) } }
    }

    /** Another app's content:// only (the content resolver risks page above). */
    private fun foreign(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(packageName) == false

    private fun openVoice() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

/** The words as they come, then Copy and Share; the reason when there are none. */
@Composable
private fun TranscriptScreen(model: TranscribeViewModel, onClose: () -> Unit, onEngine: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = rememberHaptics()
    val transcript by model.transcript.collectAsState()
    LaunchedEffect(transcript.step) {
        when (transcript.step) {
            Transcript.Step.DONE -> haptics.done()
            Transcript.Step.UNREADABLE, Transcript.Step.NOTHING, Transcript.Step.NO_ENGINE -> haptics.reject()
            else -> Unit
        }
    }
    val working = transcript.step == Transcript.Step.READING || transcript.step == Transcript.Step.WRITING
    // The writing runs while the screen is open and nothing runs once it is left: the screen stays on until it is done
    // (a window flag, no permission) https://developer.android.com/develop/background-work/background-tasks/awake/screen-on
    val window = (context as? Activity)?.window
    DisposableEffect(working) {
        if (working) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val pageScroll = rememberScrollState()
    FloatingFrame(
        bottom = 0.dp,
        scroll = pageScroll,
        top = { FloatingTop("Transcript", trailing = { FloatingAction(AppIcons.Close, "Close", onClose) }) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(pageScroll).padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (working) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LoadingMark(Modifier, 28.dp, true, transcript.progress)
                            Text(
                                progressLabel(transcript),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (transcript.step == Transcript.Step.CANCELLED) {
                        Text("Cancelled", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val reason = when (transcript.step) {
                        Transcript.Step.NO_ENGINE -> "Language needs its Whisper model to write out a sound"
                        Transcript.Step.UNREADABLE -> "This sound cannot be read"
                        Transcript.Step.NOTHING -> "No words in this sound"
                        else -> null
                    }
                    if (reason != null) Text(reason, color = MaterialTheme.colorScheme.error)
                    if (transcript.text.isNotEmpty()) {
                        SelectionContainer { Text(transcript.text, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
            // The buttons in measured, balanced rows (HomeScreen's BalancedButtons), their labels wrapping between words.
            val has = transcript.text.isNotBlank()
            BalancedButtons(
                if (transcript.step == Transcript.Step.NO_ENGINE) {
                    listOf(ButtonSpec("Choose an engine") { BoldButton(onClick = onEngine, modifier = it) { GroupLabel("Choose an engine") } })
                } else {
                    buildList {
                        if (working) {
                            add(ButtonSpec("Cancel") { BoldButton(onClick = { haptics.reject(); model.cancel() }, modifier = it) { GroupLabel("Cancel") } })
                        }
                        add(ButtonSpec("Copy") {
                            BoldButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Transcript", transcript.text))
                                haptics.done()
                            }, enabled = has, modifier = it) { GroupLabel("Copy") }
                        })
                        add(ButtonSpec("Share") {
                            BoldButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, transcript.text)
                                context.startActivity(Intent.createChooser(send, null))
                            }, enabled = has, modifier = it) { GroupLabel("Share") }
                        })
                    }
                },
                Modifier.widthIn(max = 520.dp)
            )
        }
    }
}

/**
 * What is being done: "Minute 3 of 12" once the sound is a minute or more (the minute the words written so far are
 * in, from their times against the length in the track's format), else its length. The times are the words'
 * own, counted as RecognitionPart counts them https://developer.android.com/reference/android/speech/RecognitionPart
 */
private fun progressLabel(transcript: Transcript): String {
    if (transcript.step == Transcript.Step.READING) return "Reading the sound…"
    val total = (transcript.durationMs + 59_999L) / 60_000L
    return when {
        total >= 2 -> "Minute ${minOf(total, transcript.positionMs / 60_000L + 1)} of $total"
        transcript.durationMs > 0 -> "Writing out ${minutes((transcript.durationMs / 1000L).toInt())}…"
        else -> "Writing out…"
    }
}

/** "2 min 05" or "40 s", for the length being written out. */
private fun minutes(seconds: Int): String = if (seconds < 60) "$seconds s" else "${seconds / 60} min %02d".format(seconds % 60)
