package com.yaz.voice.recognition

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import com.yaz.voice.core.voice.Dictation
import com.yaz.voice.core.voice.PcmSource
import java.io.InputStream

/**
 * The sound an app hands in itself instead of the microphone: RecognizerIntent.EXTRA_AUDIO_SOURCE, a stream of PCM
 * whose encoding, rate and channels come in EXTRA_AUDIO_SOURCE_ENCODING, _SAMPLING_RATE and _CHANNEL_COUNT
 * https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE
 * Only 16-bit PCM is read (the documented default); it is mixed down to mono and brought to 16 kHz by SMS's
 * Transcriber.Resampler (linear, across buffers)
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/core/voice/Transcriber.kt
 * The end of the stream ends the dictation as a file does (-1). At most [MAX_SECONDS] are read.
 */
class StreamSource(
    private val stream: ParcelFileDescriptor,
    private val rate: Int,
    private val channels: Int,
) : PcmSource {
    private var input: InputStream? = null
    private val ch = channels.coerceIn(1, 8)
    private val raw = ByteArray(4096 * ch)
    private var carry = -1 // a byte left over from an odd read
    private val ready = ShortArray(Dictation.SAMPLE_RATE)
    private var readyFrom = 0
    private var readyTo = 0
    private var position = 0.0
    private var last = 0f
    private var given = 0L
    private var over = false

    override fun start() {
        input = ParcelFileDescriptor.AutoCloseInputStream(stream)
    }

    override fun read(buffer: ShortArray): Int {
        while (readyFrom == readyTo) {
            if (over) return -1
            fill()
        }
        val n = minOf(buffer.size, readyTo - readyFrom)
        System.arraycopy(ready, readyFrom, buffer, 0, n)
        readyFrom += n
        given += n
        if (given >= MAX_SECONDS * Dictation.SAMPLE_RATE) over = true
        return n
    }

    /** Reads the next bytes, little-endian 16-bit, into mono 16 kHz samples (SMS's Resampler.push). */
    private fun fill() {
        readyFrom = 0
        readyTo = 0
        val got = runCatching { input?.read(raw) ?: -1 }.getOrDefault(-1)
        if (got <= 0) {
            over = true
            return
        }
        val bytes = if (carry >= 0) byteArrayOf(carry.toByte()) + raw.copyOf(got) else raw.copyOf(got)
        carry = if (bytes.size % 2 == 1) bytes.last().toInt() and 0xFF else -1
        val samples = bytes.size / 2
        val frames = samples / ch
        if (frames == 0) return
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until ch) {
                val i = (f * ch + c) * 2
                sum += ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toFloat()
            }
            sum / ch
        }
        val step = rate.toDouble() / Dictation.SAMPLE_RATE
        // Position runs in input frames; -1 is the last frame of the buffer before.
        while (position < frames - 1 && readyTo < ready.size) {
            val i = kotlin.math.floor(position).toInt()
            val t = (position - i).toFloat()
            val a = if (i < 0) last else mono[i]
            val b = mono[i + 1]
            ready[readyTo++] = (a + (b - a) * t).toInt().coerceIn(-32768, 32767).toShort()
            position += step
        }
        position -= frames
        last = mono[frames - 1]
    }

    override fun release() {
        runCatching { input?.close() ?: stream.close() }
        input = null
    }

    companion object {
        /** Ten minutes, as SMS caps a voice message. */
        const val MAX_SECONDS = 600

        /** Whether the stream's encoding is one this source reads (16-bit PCM, the default). */
        fun readable(encoding: Int): Boolean = encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_DEFAULT
    }
}
