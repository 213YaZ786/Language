package com.yaz.language.feature.home

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Listen: the phone's own text to speech engine (GrapheneOS ships its own), through android.speech.tts.TextToSpeech as
 * Android's reference describes it: the engine starts asynchronously and calls OnInitListener, setLanguage answers
 * LANG_MISSING_DATA or LANG_NOT_SUPPORTED when the engine has no voice, speak() takes at most
 * getMaxSpeechInputLength() characters (longer text is cut into several utterances queued with QUEUE_ADD), and an
 * UtteranceProgressListener tells when it ends
 * https://developer.android.com/reference/android/speech/tts/TextToSpeech
 * https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener
 * offline-translator's SystemTtsText.kt (SSML parsing for the engine it provides) does not fit: Language provides no
 * engine, it only speaks through the phone's. The engine is bound only while the screen lives: [shutdown] when it goes.
 * The text is never logged.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = false
    private var waiting: Pair<String, Locale>? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** The phone's engine has no voice for the language, or no engine started. */
    private val _problem = MutableStateFlow(false)
    val problem: StateFlow<Boolean> = _problem.asStateFlow()

    fun speak(text: String, locale: Locale) {
        _problem.value = false
        val started = engine
        if (started == null) {
            waiting = text to locale
            engine = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    waiting?.let { (t, l) -> sayNow(t, l) }
                } else {
                    _problem.value = true
                }
                waiting = null
            }
        } else if (ready) {
            sayNow(text, locale)
        }
    }

    private fun sayNow(text: String, locale: Locale) {
        val tts = engine ?: return
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            _problem.value = true
            return
        }
        val parts = chunks(text, TextToSpeech.getMaxSpeechInputLength())
        val last = "listen-${parts.size - 1}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == last) _speaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _speaking.value = false
            }
        })
        _speaking.value = true
        parts.forEachIndexed { index, part ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(part, mode, null, "listen-$index") == TextToSpeech.ERROR) {
                _speaking.value = false
                _problem.value = true
                return
            }
        }
    }

    fun stop() {
        engine?.stop()
        _speaking.value = false
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        waiting = null
        _speaking.value = false
    }

    internal companion object {
        /** The text in pieces of at most [max] characters, cut after whitespace where there is some. */
        fun chunks(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = mutableListOf<String>()
            var from = 0
            while (from < text.length) {
                var to = minOf(from + max, text.length)
                if (to < text.length) {
                    val cut = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), to - 1)
                    if (cut > from) to = cut + 1
                }
                out += text.substring(from, to)
                from = to
            }
            return out
        }
    }
}
