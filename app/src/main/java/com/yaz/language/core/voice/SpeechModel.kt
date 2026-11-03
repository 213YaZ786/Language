package com.yaz.language.core.voice

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Where an app keeps which speech model the user chose (the model's file name, [SpeechModel.SYSTEM], or empty). */
interface SpeechChoice {
    var name: String
}

/**
 * The speech model whisper.cpp reads: OpenAI's Whisper (MIT) fine-tuned by FUTO for a short audio context (ACFT,
 * https://github.com/futo-org/whisper-acft, MIT), q8_0. Not in the app: fetched once, only when
 * the user asks, and kept only when its SHA-256 is
 * the one written here. A newer model comes with an update of the app,
 * which then offers it and lets the old one go.
 * From SMS's core/voice/SpeechModel.kt; the app gives it the place its choice is kept ([choice]), the language its
 * phone recognizer is asked about ([language]) and whether it may use the network now ([allowed]).
 */
class SpeechModel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val choice: SpeechChoice,
    private val language: () -> Locale = { Locale.getDefault() },
    private val allowed: () -> Boolean = { true },
) {

    /**
     * One model the app knows, pinned. [shortContext]: the model was tuned to read only the length of the sound
     * (whisper-acft), so whisper is given an audio context sized to the phrase (Whisper.transcribe).
     */
    data class Pin(
        val name: String,
        val label: String,
        val quality: String,
        val bytes: Long,
        val sha256: String,
        val url: String,
        val shortContext: Boolean,
    )

    sealed interface State {
        data object Missing : State
        data class Fetching(val done: Float) : State
        data object Ready : State
        data object Failed : State
    }

    private val dir = File(context.filesDir, "speech")

    /** Small for phones with room to run it, base for the others. */
    val recommended: Pin = run {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (memory.totalMem >= 5L * 1024 * 1024 * 1024) SMALL else BASE
    }

    /** The user's choice, else the recommended one. */
    val pin: Pin get() = PINS.firstOrNull { it.name == choice.name } ?: recommended

    private val _system = MutableStateFlow(false)
    /** The phone has its own on-device recognizer with its language installed: nothing to fetch. */
    val system: StateFlow<Boolean> = _system.asStateFlow()

    /** The phone's own recognizer is used: chosen, or nothing else chosen nor fetched. */
    val usesSystem: Boolean
        get() = _system.value && (choice.name == SYSTEM || (choice.name.isEmpty() && !File(dir, recommended.name).exists()))

    /** Another model chosen: fetched when asked, the other one goes once it is there. */
    fun choose(pick: Pin) {
        job?.cancel()
        choice.name = pick.name
        refresh()
    }

    /** The phone's own recognizer chosen. */
    fun chooseSystem() {
        job?.cancel()
        choice.name = SYSTEM
        refresh()
    }

    /** Asks the phone again whether it hears its language itself (the app's language may have changed). */
    fun recheck(): Job =
        scope.launch {
            _system.value = SystemSpeech(context).ready(language())
            if (_state.value !is State.Fetching) refresh()
        }

    private fun refresh() {
        _state.value = if (usesSystem || file.exists()) State.Ready else State.Missing
    }

    val file: File get() = File(dir, pin.name)

    private val _state = MutableStateFlow<State>(if (File(context.filesDir, "speech/" + pin.name).exists()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    init {
        // Whether the phone hears its language itself, asked once.
        recheck()
        // A model this version no longer knows (the old ggml-base-q5_1.bin and ggml-small-q5_1.bin, an update pinned
        // another) and broken fetches go.
        scope.launch(Dispatchers.IO) { dir.listFiles()?.filter { f -> PINS.none { it.name == f.name } }?.forEach { it.delete() } }
    }

    /** Over a connection that costs by the megabyte (mobile data), the user is told first. */
    fun metered(): Boolean = runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    /** Fetches the model, checked before it is kept. Nothing is fetched while the app does not allow the network. */
    fun fetch() {
        if (_state.value is State.Fetching || _state.value == State.Ready) return
        if (!allowed()) return
        _state.value = State.Fetching(0f)
        job = scope.launch(Dispatchers.IO) {
            dir.mkdirs()
            val part = File(dir, pin.name + ".part")
            val ok = runCatching {
                val connection = open(URL(pin.url))
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var read = 0L
                    var shown = -1
                    connection.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(256 * 1024)
                            while (isActive) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                read += n
                                check(read <= pin.bytes)
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                                val percent = (read * 100 / pin.bytes).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    _state.value = State.Fetching(read.toFloat() / pin.bytes)
                                }
                            }
                        }
                    }
                    val sum = digest.digest().joinToString("") { "%02x".format(it) }
                    read == pin.bytes && sum == pin.sha256
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (ok && part.renameTo(file)) {
                dir.listFiles()?.filter { it.name != pin.name }?.forEach { it.delete() }
                _state.value = State.Ready
            } else {
                part.delete()
                _state.value = State.Failed
            }
        }
    }

    /**
     * Opens [start] once it answers 200. HttpURLConnection never follows a redirect by itself: each hop is read and
     * checked before it is taken (HTTPS, voiceinput.futo.org or dl.voiceinput.futo.org, no user part, no other port,
     * at most 5),
     * as the keyboard's dictionary download does (DictionaryRepo.open) and the update kit.
     * https://developer.android.com/reference/java/net/HttpURLConnection#setInstanceFollowRedirects(boolean)
     */
    private fun open(start: URL): HttpURLConnection {
        var url = start
        var hops = 0
        while (true) {
            check(trusted(url))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            when (connection.responseCode) {
                200 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    check(!location.isNullOrBlank() && hops++ < 5)
                    url = URL(url, location)
                }
                else -> {
                    connection.disconnect()
                    error("the model is not served")
                }
            }
        }
    }

    private fun trusted(url: URL): Boolean {
        val host = url.host.lowercase()
        return url.protocol == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
            (host == "voiceinput.futo.org" || host == "dl.voiceinput.futo.org")
    }

    fun cancel() {
        job?.cancel()
        File(dir, pin.name + ".part").delete()
        _state.value = State.Missing
    }

    /** Frees the room: the model goes, fetched again when asked. */
    fun remove() {
        cancel()
        file.delete()
    }

    companion object {
        /** FUTO's ACFT models, README "Models" of https://github.com/futo-org/whisper-acft (the sums are the pinned files'). */
        val SMALL = Pin(
            "small_acft_q8_0.bin", "Small", "More accurate", 264_464_624,
            "15ef255465a6dc582ecf1ec651a4618c7ee2c18c05570bbe46493d248d465ac4",
            "https://voiceinput.futo.org/VoiceInput/small_acft_q8_0.bin", true,
        )
        val BASE = Pin(
            "base_acft_q8_0.bin", "Base", "Quicker", 81_768_602,
            "e44f352c9aa2c3609dece20c733c4ad4a75c28cd9ab07d005383df55fa96efc4",
            "https://voiceinput.futo.org/VoiceInput/base_acft_q8_0.bin", true,
        )
        val PINS = listOf(SMALL, BASE)
        const val SYSTEM = "system"
    }
}
