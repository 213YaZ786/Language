package com.yaz.language.recognize

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import com.yaz.language.core.voice.Dictation
import com.yaz.language.core.voice.PcmSource
import com.yaz.language.recognition.StreamSource
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: the panel hears a 16 kHz mono 16-bit WAV of the app's files (extra "test_wav") instead of the
 * microphone, as Keyboard's DictationTestReceiver tries its engine on whisper.cpp's samples/jfk.wav
 * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/tests/test-vad.cpp
 * The samples after the 44-byte header go through a pipe into StreamSource, as RecognizerTestActivity hands them in.
 */
object TestSound {
    fun of(context: Context, intent: Intent): (() -> PcmSource)? {
        val name = intent.getStringExtra("test_wav") ?: return null
        return from(File(context.filesDir, File(name).name))
    }

    /**
     * The voice keyboard hears files/keyboard-test.wav while it is there (put with run-as, removed after the check),
     * as DictationTestReceiver plays a file into Keyboard's engine (test-vad.cpp above).
     */
    fun forKeyboard(context: Context): (() -> PcmSource)? = from(File(context.filesDir, "keyboard-test.wav"))

    // The WAV's samples after its 44-byte header, through a pipe, as RecognizerTestActivity hands them in
    // https://developer.android.com/reference/android/os/ParcelFileDescriptor#createPipe()
    private fun from(wav: File): (() -> PcmSource)? {
        if (!wav.exists()) return null
        return {
            val (read, write) = ParcelFileDescriptor.createPipe()
            thread {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { sink ->
                    runCatching { wav.inputStream().use { it.skip(44); it.copyTo(sink) } }
                }
            }
            StreamSource(read, Dictation.SAMPLE_RATE, 1)
        }
    }
}
