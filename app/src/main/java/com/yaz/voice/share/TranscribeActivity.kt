package com.yaz.voice.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.yaz.voice.MainActivity
import com.yaz.voice.ui.component.BoldButton
import com.yaz.voice.ui.component.FloatingAction
import com.yaz.voice.ui.component.FloatingFrame
import com.yaz.voice.ui.component.FloatingTop
import com.yaz.voice.ui.component.LoadingMark
import com.yaz.voice.ui.component.ZoneSurface
import com.yaz.voice.ui.component.rememberHaptics
import com.yaz.voice.ui.icon.AppIcons
import com.yaz.voice.ui.theme.AppSurface
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * A sound shared to Voice (ACTION_SEND of audio): its words, to copy or share. Only another app's content:// is
 * read, never a file:// nor one of Voice's own (developer.android.com/privacy-and-security/risks/content-resolver,
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
    FloatingFrame(
        bottom = 0.dp,
        top = { FloatingTop("Transcript", trailing = { FloatingAction(AppIcons.Close, "Close", onClose) }) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val working = transcript.step == Transcript.Step.READING || transcript.step == Transcript.Step.WRITING
                    if (working) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LoadingMark(Modifier, 28.dp, true)
                            Text(
                                if (transcript.step == Transcript.Step.READING) "Reading the sound…"
                                else "Writing out ${minutes(transcript.seconds)}…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val reason = when (transcript.step) {
                        Transcript.Step.NO_ENGINE -> "Voice needs its Whisper model to write out a sound"
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                if (transcript.step == Transcript.Step.NO_ENGINE) {
                    BoldButton(onClick = onEngine, modifier = Modifier.weight(1f)) { Text("Choose an engine", maxLines = 1) }
                } else {
                    val has = transcript.text.isNotBlank()
                    BoldButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Transcript", transcript.text))
                        haptics.done()
                    }, enabled = has, modifier = Modifier.weight(1f)) { Text("Copy", maxLines = 1) }
                    BoldButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, transcript.text)
                        context.startActivity(Intent.createChooser(send, null))
                    }, enabled = has, modifier = Modifier.weight(1f)) { Text("Share", maxLines = 1) }
                }
            }
        }
    }
}

/** "2 min 05" or "40 s", for the length being written out. */
private fun minutes(seconds: Int): String = if (seconds < 60) "$seconds s" else "${seconds / 60} min %02d".format(seconds % 60)
