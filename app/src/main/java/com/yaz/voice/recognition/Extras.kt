package com.yaz.voice.recognition

import android.content.Intent
import android.speech.RecognizerIntent
import com.yaz.voice.core.voice.Dictation
import java.util.Locale

/**
 * What a caller asks for in the intent of a recognition (RecognizerIntent's extras
 * https://developer.android.com/reference/android/speech/RecognizerIntent), read the same way by the recognition
 * service (VoiceRecognitionService) and by the RECOGNIZE_SPEECH panel (RecognizeActivity).
 */
object Extras {

    /** The language asked (EXTRA_LANGUAGE, a BCP 47 tag), or null when none is named. */
    fun language(intent: Intent): Locale? =
        intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)?.let { Locale.forLanguageTag(it) }
            ?.takeIf { it.language.isNotEmpty() }

    /** The silence that ends listening, as the app asks (half a second to ten), else the kit's three seconds. */
    fun silence(intent: Intent): Long {
        val asked = intent.extras?.get(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
        val ms = (asked as? Number)?.toLong() ?: return Dictation.SILENCE_MS
        return ms.coerceIn(500L, 10_000L)
    }

    /** The words the app expects (EXTRA_BIASING_STRINGS), given to whisper as its prompt, 300 characters at most. */
    fun prompt(intent: Intent): String? =
        intent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
            ?.joinToString(", ")?.take(300)?.takeIf { it.isNotEmpty() }
}
