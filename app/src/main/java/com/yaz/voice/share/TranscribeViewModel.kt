package com.yaz.voice.share

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.core.voice.AudioDecode
import com.yaz.voice.core.voice.Dictation
import com.yaz.voice.core.voice.SpeechModel
import com.yaz.voice.core.voice.Whisper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Where a shared sound stands: read, written out phrase by phrase, done, or what stopped it. [durationMs] is the
 * sound's length from its format (0 when it says none), [positionMs] where the words written so far end.
 */
data class Transcript(
    val step: Step = Step.READING,
    val text: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
) {
    enum class Step { READING, WRITING, DONE, CANCELLED, NO_ENGINE, UNREADABLE, NOTHING }

    /** The share of the sound written out, 0 to 1; 0 when the length is not known. */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * A shared sound written out: opened by the voice kit's AudioDecode.DecodedSource (SMS's Transcriber decoding, Android's
 * sandboxed extractor and decoders, decoded as it is read, no length cap), then heard by Dictation, so its phrases
 * come one by one as the try zone's do, with their words' times, in whatever language they are spoken (whisper finds
 * it: an empty language is "auto"). Only the sound's own end ends it, never its silences. Ported from SMS's
 * Transcriber.hear https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/core/voice/Transcriber.kt
 * and Transcribro's audio file request (issue 28) https://github.com/soupslurpr/Transcribro/issues/28
 * It lives with the screen (the ViewModel survives a turn) and stops with it.
 */
class TranscribeViewModel(context: Context, private val speech: VoiceSpeech) : ViewModel() {

    private val app = context.applicationContext
    private val dictation = Dictation(app)
    private val _transcript = MutableStateFlow(Transcript())
    val transcript: StateFlow<Transcript> = _transcript.asStateFlow()
    private var started = false

    private val listener = object : Dictation.Listener {
        override fun partial(text: String) = Unit
        override fun committed(text: String) = _transcript.update {
            it.copy(text = if (it.text.isEmpty()) text else it.text + " " + text)
        }
        // The words' times are from the start of the sound: where the last one ends is how far the writing has come
        override fun committedWords(text: String, words: List<Whisper.Word>) = _transcript.update {
            it.copy(
                text = if (it.text.isEmpty()) text else it.text + " " + text,
                positionMs = maxOf(it.positionMs, words.maxOfOrNull { word -> word.endMs } ?: 0L),
            )
        }
        override fun stopped(why: Dictation.Why) = _transcript.update {
            if (it.step == Transcript.Step.CANCELLED) it
            else it.copy(step = if (it.text.isBlank()) Transcript.Step.NOTHING else Transcript.Step.DONE)
        }
    }

    /** Reads [uri] once (a turn of the screen keeps the work going). */
    fun start(uri: Uri) {
        if (started) return
        started = true
        viewModelScope.launch {
            val ready = Whisper.loaded && speech.model.state.value == SpeechModel.State.Ready && speech.model.file.exists()
            if (!ready) {
                _transcript.value = Transcript(Transcript.Step.NO_ENGINE)
                return@launch
            }
            val source = withContext(Dispatchers.IO) { runCatching { AudioDecode.DecodedSource.open(app, uri) }.getOrNull() }
            if (source == null) {
                _transcript.value = Transcript(Transcript.Step.UNREADABLE)
                return@launch
            }
            // Cancelled while the sound was being opened: nothing to start
            if (_transcript.value.step == Transcript.Step.CANCELLED) {
                source.release()
                return@launch
            }
            _transcript.value = Transcript(Transcript.Step.WRITING, durationMs = source.durationMs)
            val ok = dictation.start(
                Dictation.Engine.WHISPER, Locale.ROOT, speech.model.file, listener,
                source = source,
                timings = true,
                stopOnSilence = false,
            )
            if (!ok) {
                source.release()
                _transcript.value = Transcript(Transcript.Step.UNREADABLE)
            }
        }
    }

    /**
     * Stops the writing, keeping the words so far. A ViewModel's work ends with its scope
     * https://developer.android.com/topic/libraries/architecture/viewmodel
     */
    fun cancel() {
        val step = _transcript.value.step
        if (step != Transcript.Step.READING && step != Transcript.Step.WRITING) return
        _transcript.update { it.copy(step = Transcript.Step.CANCELLED) }
        dictation.stop(false)
    }

    override fun onCleared() {
        dictation.stop(false)
    }
}
