package com.yaz.language.recognize

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.yaz.language.MainActivity
import com.yaz.language.core.VoiceSpeech
import com.yaz.language.feature.home.Phase
import com.yaz.language.ui.component.rememberHaptics
import com.yaz.language.ui.glass.LocalGlass
import com.yaz.language.ui.glass.rememberGlassLook
import com.yaz.language.ui.theme.AppTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * The panel an app's mic opens: RecognizerIntent.ACTION_RECOGNIZE_SPEECH answered by Language. It listens at once and
 * hands the words back as Transcribro's ActionRecognizeSpeechScreen does (EXTRA_RESULTS and EXTRA_CONFIDENCE_SCORES in
 * the result, and to EXTRA_RESULTS_PENDINGINTENT with EXTRA_RESULTS_PENDINGINTENT_BUNDLE's extras)
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/action_recognize_speech/ActionRecognizeSpeechScreen.kt
 * The window is translucent and handles its configuration changes, so a turn of the screen never stops listening
 * (Transcribro issue 85). Leaving it cancels the dictation: no microphone is left open.
 * The state lives in RecognizeViewModel, the sheet in RecognizePanel.
 */
class RecognizeActivity : ComponentActivity() {

    private val speech: VoiceSpeech by inject()
    private val model: RecognizeViewModel by viewModel()
    private val shown = MutableTransitionState(false)
    private var closing by mutableStateOf(false)
    private lateinit var asked: Asked

    // Android's runtime permission request through the activity result API
    // https://developer.android.com/training/permissions/requesting
    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else model.denied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        asked = Asked.of(this, intent, speech.language())
        // The words, once the dictation ends with some, go back to the caller.
        lifecycleScope.launch { deliver(model.result.filterNotNull().first()) }
        setContent { Panel() }
        if (savedInstanceState == null) begin()
    }

    // Back from the page of Language in Android's settings with the microphone allowed: listening starts.
    override fun onResume() {
        super.onResume()
        if (model.problem.value == Problem.MICROPHONE && granted()) begin()
    }

    // The panel is no longer in front: nothing keeps listening.
    override fun onStop() {
        if (!isFinishing) model.stop(false)
        super.onStop()
    }

    private fun granted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Listens, after asking for the microphone when it is not allowed yet. */
    private fun begin() {
        if (granted()) model.start(asked) else ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** The mic: ends listening (the words still held are written out), or starts again. */
    private fun onMic() {
        when (model.heard.value.phase) {
            Phase.LISTENING -> model.stop(true)
            Phase.IDLE -> begin()
            else -> Unit
        }
    }

    /** The button of an error: Android's page for Language, Language itself, or another try. */
    private fun onAction() {
        when (model.problem.value) {
            Problem.MICROPHONE -> startActivity(VoiceSpeech.appSettingsIntent(this))
            Problem.ENGINE -> {
                startActivity(Intent(this, MainActivity::class.java))
                cancel()
            }
            null -> begin()
        }
    }

    /** Tap outside the sheet, or Back: nothing is handed back. */
    private fun cancel() {
        model.stop(false)
        setResult(RESULT_CANCELED)
        close()
    }

    /** The sheet slides down, then the activity ends (Panel). */
    private fun close() {
        closing = true
        shown.targetState = false
    }

    /**
     * The words back to the caller, ported from Transcribro's ActionRecognizeSpeechScreen onResults: EXTRA_RESULTS
     * and EXTRA_CONFIDENCE_SCORES (1.0) in the result, and sent to EXTRA_RESULTS_PENDINGINTENT with the same
     * extras in a fill-in intent, those of EXTRA_RESULTS_PENDINGINTENT_BUNDLE added to it as RecognizerIntent
     * describes https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_RESULTS_PENDINGINTENT_BUNDLE
     * A pending intent that was cancelled is no failure.
     */
    private fun deliver(text: String) {
        val transcripts = arrayListOf(text)
        val scores = floatArrayOf(1.0F)
        val results = Intent()
            .putExtra(RecognizerIntent.EXTRA_RESULTS, transcripts)
            .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, scores)
        val pending = runCatching {
            IntentCompat.getParcelableExtra(intent, RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, PendingIntent::class.java)
        }.getOrNull()
        if (pending != null) {
            val bundle = runCatching { intent.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE) }.getOrNull()
            val fill = Intent(results)
            if (bundle != null) {
                fill.putExtras(Bundle(bundle).apply {
                    putStringArrayList(RecognizerIntent.EXTRA_RESULTS, transcripts)
                    putFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, scores)
                })
            }
            try {
                pending.send(this, RESULT_OK, fill)
            } catch (gone: PendingIntent.CanceledException) {
                // the app that asked no longer waits for it
            }
        }
        setResult(RESULT_OK, results)
        close()
    }

    /**
     * The window of the panel: the app's theme and glass over a window that stays see-through (no ground drawn, unlike
     * AppSurface), bars transparent as AppSurface sets them.
     */
    @Composable
    private fun Panel() {
        val dark = isSystemInDarkTheme()
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
            )
            onDispose { }
        }
        AppTheme(darkTheme = dark) {
            val look = rememberGlassLook(MaterialTheme.colorScheme, true)
            CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                val heard by model.heard.collectAsState()
                val problem by model.problem.collectAsState()
                val haptics = rememberHaptics()

                // The sheet comes up when the window opens.
                LaunchedEffect(Unit) { shown.targetState = true }
                // The sheet is down: the activity ends.
                LaunchedEffect(closing, shown.currentState, shown.targetState) {
                    if (closing && !shown.currentState && !shown.targetState) finish()
                }
                BackHandler(enabled = !closing, onBack = ::cancel)

                // A feel for listening starting and ending, and for a failure, as the try zone's.
                val listening = heard.phase == Phase.LISTENING
                var wasListening by remember { mutableStateOf(listening) }
                LaunchedEffect(listening) {
                    if (listening != wasListening) {
                        if (listening) haptics.firm() else haptics.done()
                        wasListening = listening
                    }
                }
                LaunchedEffect(heard.note) { if (heard.note != null) haptics.reject() }

                RecognizePanel(
                    shown = shown,
                    asked = asked,
                    heard = heard,
                    problem = problem,
                    onMic = { if (!closing) { haptics.tick(); onMic() } },
                    onAction = { if (!closing) { haptics.tick(); onAction() } },
                    onCancel = { if (!closing) cancel() },
                )
            }
        }
    }
}
