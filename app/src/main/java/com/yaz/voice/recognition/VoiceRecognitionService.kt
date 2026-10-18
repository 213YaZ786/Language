package com.yaz.voice.recognition

import android.content.ContextParams
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.core.voice.Dictation
import com.yaz.voice.core.voice.MicSource
import com.yaz.voice.core.voice.SpeechModel
import com.yaz.voice.core.voice.Whisper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

/**
 * Voice as the phone's speech recognizer: apps that call SpeechRecognizer (or Android's voice input setting) are heard
 * by the voice kit's Dictation, the phone's on-device recognizer or whisper.
 *
 * Sources:
 * - Transcribro's MainRecognitionService (ISC): one session at a time (ERROR_RECOGNIZER_BUSY), the microphone opened
 *   with a context whose next attribution source is the caller's (ContextParams.setNextAttributionSource), so the
 *   microphone light names the app that asked; partial results carry all the words heard so far; begin and end of
 *   speech; the results at the end
 *   https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/recognitionservice/MainRecognitionService.kt
 * - AOSP's CTS recognition service for the callbacks of Android 13 and 14 (segmentResults, endOfSegmentedSession,
 *   languageDetection, onCheckRecognitionSupport, onTriggerModelDownload, getMaxConcurrentSessionsCount)
 *   https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/tests/voiceRecognition/src/android/voicerecognition/cts/CtsRecognitionService.java
 * - the extras read are RecognizerIntent's https://developer.android.com/reference/android/speech/RecognizerIntent
 *
 * Nothing runs while no app is bound; a model is never fetched here (only on a tap in Voice).
 */
class VoiceRecognitionService : RecognitionService() {

    private val speech: VoiceSpeech by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: Session? = null

    override fun getMaxConcurrentSessionsCount(): Int = 1

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        callback ?: return
        if (session != null) {
            safely { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        val made = Session(intent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), callback)
        session = made
        scope.launch { made.begin() }
    }

    // RecognitionService gives one Callback per session, the same at its start, stop and cancel
    // https://developer.android.com/reference/android/speech/RecognitionService.Callback
    override fun onStopListening(callback: Callback?) {
        session?.takeIf { it.callback === callback }?.stop()
    }

    override fun onCancel(callback: Callback?) {
        session?.takeIf { it.callback === callback }?.cancel()
    }

    override fun onDestroy() {
        session?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The languages Voice can hear now (installed: whisper's when its model is here, the phone's language when its own
     * recognizer has it) and those it could after a fetch made in Voice (supported). As the CTS service answers
     * through SupportCallback.onSupportResult.
     */
    @RequiresApi(33)
    override fun onCheckRecognitionSupport(intent: Intent, supportCallback: SupportCallback) {
        scope.launch {
            val whisper = whisperLanguages()
            val ready = speech.model.state.value == SpeechModel.State.Ready && speech.model.file.exists() && Whisper.loaded
            if (!speech.model.system.value) speech.model.recheck().join()
            val installed = buildList {
                if (ready) addAll(whisper)
                if (speech.model.system.value) add(speech.language().toLanguageTag())
            }.distinct()
            val support = RecognitionSupport.Builder()
                .setInstalledOnDeviceLanguages(installed)
                .setSupportedOnDeviceLanguages(if (Whisper.loaded) whisper.filterNot { it in installed } else emptyList())
                .build()
            safely { supportCallback.onSupportResult(support) }
        }
    }

    /** A model is fetched only on the user's tap inside Voice: an app asking gets nothing started. */
    override fun onTriggerModelDownload(intent: Intent) = Unit

    @RequiresApi(34)
    override fun onTriggerModelDownload(
        intent: Intent,
        attributionSource: android.content.AttributionSource,
        listener: ModelDownloadListener,
    ) {
        safely { listener.onError(SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) }
    }

    /** The languages whisper knows, as language tags (whisper's codes are ISO 639-1, Locale's own). */
    private fun whisperLanguages(): List<String> =
        if (!Whisper.loaded) emptyList() else Locale.getISOLanguages().filter { Whisper.knows(it) }

    /** One app's listening, from its start to its last callback. */
    private inner class Session(private val intent: Intent, val callback: Callback) : Dictation.Listener {
        private val attribution = createContext(
            ContextParams.Builder().setNextAttributionSource(callback.callingAttributionSource).build()
        )
        private val dictation = Dictation(attribution)
        private val partials = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        private val segmented = Build.VERSION.SDK_INT >= 33 &&
            intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION) != null
        private val detection = Build.VERSION.SDK_INT >= 34 &&
            intent.getBooleanExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, false)
        private val words = StringBuilder()
        private var began = false
        private var over = false

        suspend fun begin() {
            val asked = Extras.language(intent)
            // No language named and detection asked: whisper finds it from the sound (an empty code is "auto")
            val language = asked ?: if (detection) Locale.ROOT else speech.language()
            val handed = streamOf(intent)
            if (handed.bad) return fail(SpeechRecognizer.ERROR_AUDIO)
            val stream = handed.source
            val engine = chooseEngine(language, stream != null) ?: run {
                stream?.release()
                return fail(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            }
            val source = when {
                engine == Dictation.Engine.SYSTEM -> null
                stream != null -> stream
                else -> MicSource(attribution)
            }
            safely { callback.readyForSpeech(Bundle()) }
            val started = dictation.start(
                engine, language, speech.model.file, this,
                source = source,
                silenceMs = Extras.silence(intent),
                prompt = Extras.prompt(intent),
            )
            if (!started) fail(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }

        /**
         * The phone's own recognizer when it has the language asked (the phone's) and the sound is the microphone's;
         * whisper when its model is here; else none. The order is VoiceSpeech.engine's (Keyboard's DictationController).
         */
        private suspend fun chooseEngine(language: Locale, stream: Boolean): Dictation.Engine? {
            val chosen = speech.engine()
            val whisperReady = Whisper.loaded && speech.model.state.value == SpeechModel.State.Ready && speech.model.file.exists()
            val phoneLanguage = language.language == speech.language().language
            return when {
                chosen == Dictation.Engine.SYSTEM && !stream && phoneLanguage -> Dictation.Engine.SYSTEM
                whisperReady -> Dictation.Engine.WHISPER
                chosen == Dictation.Engine.SYSTEM && !stream -> Dictation.Engine.SYSTEM
                else -> null
            }
        }

        fun stop() = dictation.stop(true)

        fun cancel() {
            over = true
            dictation.stop(false)
            finish()
        }

        override fun partial(text: String) {
            speech()
            if (partials) safely { callback.partialResults(bundle(joined(text))) }
        }

        override fun committed(text: String) {
            speech()
            if (words.isNotEmpty()) words.append(' ')
            words.append(text)
            if (segmented && Build.VERSION.SDK_INT >= 33) safely { callback.segmentResults(bundle(text)) }
            else if (partials) safely { callback.partialResults(bundle(words.toString())) }
        }

        override fun level(rmsDb: Float) {
            if (!over) safely { callback.rmsChanged(rmsDb) }
        }

        override fun language(code: String) {
            if (!detection || over || Build.VERSION.SDK_INT < 34) return
            val result = Bundle().apply {
                putString(SpeechRecognizer.DETECTED_LANGUAGE, Locale.forLanguageTag(code).toLanguageTag())
                putInt(
                    SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                    SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT,
                )
            }
            safely { callback.languageDetection(result) }
        }

        override fun stopped(why: Dictation.Why) {
            if (over) return
            over = true
            if (began) safely { callback.endOfSpeech() }
            when {
                segmented && Build.VERSION.SDK_INT >= 33 && why != Dictation.Why.FAILED &&
                    why != Dictation.Why.UNAVAILABLE && why != Dictation.Why.NO_MICROPHONE_RIGHT ->
                    safely { callback.endOfSegmentedSession() }
                words.isNotEmpty() -> safely { callback.results(bundle(words.toString())) }
                else -> safely { callback.error(errorFor(why)) }
            }
            finish()
        }

        private fun speech() {
            if (began || over) return
            began = true
            safely { callback.beginningOfSpeech() }
        }

        private fun joined(partial: String) = if (words.isEmpty()) partial else "$words $partial"

        private fun fail(error: Int) {
            over = true
            safely { callback.error(error) }
            finish()
        }

        private fun finish() {
            if (session === this) session = null
        }
    }

    /**
     * The sound an app handed in (EXTRA_AUDIO_SOURCE with its encoding, rate and channels,
     * https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE): none (the
     * microphone), one to read, or one that cannot be read ([bad]).
     */
    private class Handed(val source: StreamSource?, val bad: Boolean = false)

    private companion object {
        /** The sound the app gives itself (EXTRA_AUDIO_SOURCE, Android 13), else the microphone. */
        fun streamOf(intent: Intent): Handed {
            if (Build.VERSION.SDK_INT < 33) return Handed(null)
            val fd = intent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor::class.java)
                ?: return Handed(null)
            val encoding = intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val rate = intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Dictation.SAMPLE_RATE)
            val channels = intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            if (!StreamSource.readable(encoding) || rate !in 4_000..192_000 || channels !in 1..8) {
                runCatching { fd.close() }
                return Handed(null, bad = true)
            }
            return Handed(StreamSource(fd, rate, channels))
        }

        fun bundle(text: String) = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }

        fun errorFor(why: Dictation.Why): Int = when (why) {
            Dictation.Why.NO_SPEECH -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            Dictation.Why.NO_MICROPHONE_RIGHT -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            Dictation.Why.UNAVAILABLE -> SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            Dictation.Why.FAILED -> SpeechRecognizer.ERROR_AUDIO
            Dictation.Why.USER, Dictation.Why.SILENCE -> SpeechRecognizer.ERROR_NO_MATCH
        }

        /** A caller that went away makes its callback throw: nothing more to tell it. */
        inline fun safely(block: () -> Unit) {
            runCatching(block)
        }
    }
}
