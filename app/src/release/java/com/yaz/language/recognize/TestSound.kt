package com.yaz.language.recognize

import android.content.Context
import android.content.Intent
import com.yaz.language.core.voice.PcmSource

/**
 * Release builds: the panel always listens to the microphone. The debug build's TestSound reads a recorded file
 * instead, as Keyboard's DictationTestReceiver tries its engine on whisper.cpp's samples/jfk.wav
 * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/tests/test-vad.cpp
 */
object TestSound {
    @Suppress("UNUSED_PARAMETER")
    fun of(context: Context, intent: Intent): (() -> PcmSource)? = null

    // Same as the debug build's forKeyboard, never a file here (DictationTestReceiver's FileSound,
    // https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/tests/test-vad.cpp)
    @Suppress("UNUSED_PARAMETER")
    fun forKeyboard(context: Context): (() -> PcmSource)? = null
}
