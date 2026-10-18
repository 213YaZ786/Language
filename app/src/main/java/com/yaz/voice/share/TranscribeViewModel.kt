package com.yaz.voice.share

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.core.voice.AudioDecode
import com.yaz.voice.core.voice.Dictation
import com.yaz.voice.core.voice.SamplesSource
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

/** Where a shared sound stands: read, written out phrase by phrase, done, or what stopped it. */
data class Transcript(
    val step: Step = Step.READING,
    val text: String = "",
    val seconds: Int = 0,
) {
    enum class Step { READING, WRITING, DONE, NO_ENGINE, UNREADABLE, NOTHING }
}

/**
 * A shared sound written out: decoded by the voice kit's AudioDecode (SMS's Transcriber, Android's sandboxed
 * extractor and decoders), then heard by Dictation through SamplesSource, so its phrases come one by one as the
 * try zone's do, in whatever language they are spoken (whisper finds it: an empty language is "auto"). Ported from
 * SMS's Transcriber.hear https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/core/voice/Transcriber.kt
 * and Transcribro's audio file request (issue 28) https://github.com/soupslurpr/Transcribro/issues/28
 * Ten minutes at most, as SMS's voice messages.
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
        override fun stopped(why: Dictation.Why) = _transcript.update {
            it.copy(step = if (it.text.isBlank()) Transcript.Step.NOTHING else Transcript.Step.DONE)
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
            val samples = withContext(Dispatchers.IO) { runCatching { AudioDecode.decode(app, uri) }.getOrNull() }
            if (samples == null) {
                _transcript.value = Transcript(Transcript.Step.UNREADABLE)
                return@launch
            }
            _transcript.value = Transcript(Transcript.Step.WRITING, seconds = samples.size / Dictation.SAMPLE_RATE)
            // The sound's own end ends it, never its silences
            val ok = dictation.start(
                Dictation.Engine.WHISPER, Locale.ROOT, speech.model.file, listener,
                source = SamplesSource(samples),
                silenceMs = 600_000L,
            )
            if (!ok) _transcript.value = Transcript(Transcript.Step.UNREADABLE)
        }
    }

    override fun onCleared() {
        dictation.stop(false)
    }
}
