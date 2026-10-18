package com.yaz.voice.recognize

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.core.voice.Dictation
import com.yaz.voice.core.voice.PcmSource
import com.yaz.voice.feature.home.Heard
import com.yaz.voice.feature.home.Phase
import com.yaz.voice.feature.home.noteFor
import com.yaz.voice.recognition.Extras
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What the app that opened the panel asked, read once from its intent: the language, the silence that ends listening,
 * the words it expects, and the line it wants shown (RecognizerIntent's extras
 * https://developer.android.com/reference/android/speech/RecognizerIntent), 200 characters of the line at most.
 */
class Asked(
    val language: Locale,
    val silenceMs: Long,
    val prompt: String?,
    val caption: String?,
    /** A recorded sound in place of the microphone: debug builds only (TestSound), null in a release. */
    val sound: (() -> PcmSource)? = null,
) {
    companion object {
        /** Reads the extras through the ones the recognition service reads too (Extras). */
        fun of(context: Context, intent: Intent, phoneLanguage: Locale): Asked = Asked(
            language = Extras.language(intent) ?: phoneLanguage,
            silenceMs = Extras.silence(intent),
            prompt = Extras.prompt(intent),
            caption = intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT)?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            sound = TestSound.of(context, intent),
        )
    }
}

/** What the panel cannot go on without, and has a button for. */
enum class Problem { MICROPHONE, ENGINE }

/**
 * The dictation of the RECOGNIZE_SPEECH panel. Copied from HomeViewModel (the try zone's, itself after Keyboard's
 * DictationController, https://github.com/HeliBorg/HeliBoard, app/src/main/java/helium314/keyboard/latin/dictation/
 * DictationController.kt) with what the panel adds: the caller's language, silence and words, and the text handed
 * back when the dictation ends with words (Transcribro's SpeechRecognizerViewModel keeps the same state
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/action_recognize_speech/SpeechRecognizerViewModel.kt).
 * Dictation is used on the main thread, as its notes say.
 */
class RecognizeViewModel(context: Context, private val speech: VoiceSpeech) : ViewModel() {

    private val dictation = Dictation(context.applicationContext)
    private val _heard = MutableStateFlow(Heard())
    val heard: StateFlow<Heard> = _heard.asStateFlow()
    private val _problem = MutableStateFlow<Problem?>(null)
    val problem: StateFlow<Problem?> = _problem.asStateFlow()
    private val _result = MutableStateFlow<String?>(null)

    /** The words to hand back, set once when the dictation ended with some. */
    val result: StateFlow<String?> = _result.asStateFlow()
    private var starting: Job? = null

    private val listener = object : Dictation.Listener {
        override fun partial(text: String) = _heard.update { it.copy(partial = text) }

        override fun committed(text: String) = _heard.update {
            it.copy(committed = if (it.committed.isBlank()) text else it.committed + " " + text, partial = "")
        }

        override fun stopped(why: Dictation.Why) {
            val words = _heard.value.text.trim()
            val handed = words.isNotBlank() && (why == Dictation.Why.USER || why == Dictation.Why.SILENCE)
            if (why == Dictation.Why.NO_MICROPHONE_RIGHT) _problem.value = Problem.MICROPHONE
            _heard.update { it.copy(phase = Phase.IDLE, partial = "", level = 0f, note = if (handed) null else noteFor(why) ?: "Nothing heard") }
            if (handed) _result.value = words
        }

        // Android's onRmsChanged passed on by the kit
        // https://developer.android.com/reference/android/speech/RecognitionListener#onRmsChanged(float)
        override fun level(rmsDb: Float) = _heard.update { if (it.phase == Phase.LISTENING) it.copy(level = rmsDb) else it }
    }

    /** The microphone was refused in the system's question. */
    fun denied() {
        _problem.value = Problem.MICROPHONE
        _heard.update { it.copy(phase = Phase.IDLE, note = noteFor(Dictation.Why.NO_MICROPHONE_RIGHT)) }
    }

    /** Starts listening, with the language, silence and words [asked]; the engine is the one that is ready. */
    fun start(asked: Asked) {
        if (_heard.value.phase != Phase.IDLE) return
        _problem.value = null
        _heard.value = Heard(phase = Phase.STARTING)
        starting = viewModelScope.launch {
            val engine = if (asked.sound != null) Dictation.Engine.WHISPER else speech.engine()
            if (engine == null) {
                _problem.value = Problem.ENGINE
                _heard.update { it.copy(phase = Phase.IDLE, note = "No speech engine yet") }
                return@launch
            }
            _heard.update { it.copy(phase = Phase.LISTENING, whisper = engine == Dictation.Engine.WHISPER) }
            val started = dictation.start(
                engine, asked.language, speech.model.file, listener,
                source = asked.sound?.invoke(),
                silenceMs = asked.silenceMs,
                prompt = asked.prompt,
            )
            if (!started) _heard.update { it.copy(phase = Phase.IDLE) }
        }
    }

    /**
     * Ends listening. With [flush] the words still held are written out first (the user tapped the mic); without,
     * they are dropped and nothing more is reported (the panel was left or cancelled).
     */
    fun stop(flush: Boolean) {
        val phase = _heard.value.phase
        if (phase == Phase.IDLE || phase == Phase.STOPPING) return
        if (flush && phase == Phase.LISTENING) {
            _heard.update { it.copy(phase = Phase.STOPPING) }
            dictation.stop(true)
        } else {
            starting?.cancel()
            dictation.stop(false)
            _heard.update { it.copy(phase = Phase.IDLE, partial = "", level = 0f) }
        }
    }

    override fun onCleared() {
        dictation.stop(false)
    }
}
