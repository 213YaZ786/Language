package com.yaz.language.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.language.core.VoiceSpeech
import com.yaz.language.core.voice.Dictation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the try zone's dictation stands. */
enum class Phase { IDLE, STARTING, LISTENING, STOPPING }

/** What the try zone shows: the words, the words still being said, the loudness, a line when it did not work. */
data class Heard(
    val phase: Phase = Phase.IDLE,
    val committed: String = "",
    val partial: String = "",
    /** Loudness in dB, as Dictation.Listener.level gives it. */
    val level: Float = 0f,
    val note: String? = null,
    /** Whisper's level is Kõnele's getRmsdb (about 15 in a quiet room, 40 loud), the phone's about -2 to 10. */
    val whisper: Boolean = false,
) {
    /** The loudness from 0 (quiet) to 1 (loud), whichever engine listens. */
    val loudness: Float get() = if (whisper) ((level - 20f) / 20f).coerceIn(0f, 1f) else (level / 10f).coerceIn(0f, 1f)

    /** Everything heard, the words said so far then the ones still coming. */
    val text: String get() = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * What a dictation that ended without words, or could not run, tells the user (moved out of HomeViewModel unchanged
 * so the RECOGNIZE_SPEECH panel says the same); one line per way Android's recognizers end without a result
 * https://developer.android.com/reference/android/speech/SpeechRecognizer#constants_1
 */
fun noteFor(why: Dictation.Why): String? = when (why) {
    Dictation.Why.NO_SPEECH -> "Nothing heard"
    Dictation.Why.NO_MICROPHONE_RIGHT -> "The microphone is not allowed"
    Dictation.Why.UNAVAILABLE -> "This engine cannot run here"
    Dictation.Why.FAILED -> "Could not listen"
    Dictation.Why.USER, Dictation.Why.SILENCE -> null
}

/**
 * The dictation of the try zone. It holds the voice kit's Dictation and follows the pattern of Keyboard's
 * DictationController (a fork of https://github.com/HeliBorg/HeliBoard, app/src/main/java/helium314/keyboard/latin/dictation/
 * DictationController.kt): the engine is chosen when listening starts, partial words replace each other, committed
 * phrases add up, and everything is released when it ends. Dictation is used on the main thread, as its notes say.
 */
class HomeViewModel(context: Context, private val speech: VoiceSpeech) : ViewModel() {

    private val dictation = Dictation(context.applicationContext)
    private val _heard = MutableStateFlow(Heard())
    val heard: StateFlow<Heard> = _heard.asStateFlow()
    private var starting: Job? = null

    private val listener = object : Dictation.Listener {
        override fun partial(text: String) = _heard.update { it.copy(partial = text) }

        override fun committed(text: String) = _heard.update {
            it.copy(committed = if (it.committed.isBlank()) text else it.committed + " " + text, partial = "")
        }

        override fun stopped(why: Dictation.Why) = _heard.update {
            it.copy(phase = Phase.IDLE, partial = "", level = 0f, note = noteFor(why))
        }

        // Android's onRmsChanged passed on by the kit
        // https://developer.android.com/reference/android/speech/RecognitionListener#onRmsChanged(float)
        override fun level(rmsDb: Float) = _heard.update { if (it.phase == Phase.LISTENING) it.copy(level = rmsDb) else it }
    }

    /** Starts listening with the engine that is ready; [noEngine] is called when none is, to lead to the settings. */
    fun start(noEngine: () -> Unit) {
        if (_heard.value.phase != Phase.IDLE) return
        _heard.update { it.copy(phase = Phase.STARTING, note = null) }
        starting = viewModelScope.launch {
            val engine = speech.engine()
            if (engine == null) {
                _heard.update { it.copy(phase = Phase.IDLE) }
                noEngine()
                return@launch
            }
            _heard.update { it.copy(phase = Phase.LISTENING, whisper = engine == Dictation.Engine.WHISPER) }
            if (!dictation.start(engine, speech.language(), speech.model.file, listener)) {
                _heard.update { it.copy(phase = Phase.IDLE) }
            }
        }
    }

    /**
     * Ends listening. With [flush] the words still held are written out first (the user ended it); without, they are
     * dropped and nothing more is reported (the screen was left).
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

    /** Empties the words. */
    fun clear() = _heard.update { it.copy(committed = "", partial = "", note = null) }

    override fun onCleared() {
        dictation.stop(false)
    }
}
