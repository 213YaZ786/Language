package com.yaz.language.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.yaz.language.core.VoiceSpeech
import com.yaz.language.core.voice.Dictation
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.feature.home.Heard
import com.yaz.language.feature.home.Phase
import com.yaz.language.feature.home.noteFor
import com.yaz.language.recognize.Problem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the voice keyboard's panel shows: where the dictation stands, what stops it, and the field it writes in. */
data class ImeState(
    val heard: Heard = Heard(),
    /** What the dictation cannot go on without, and has a button for. */
    val problem: Problem? = null,
    /** The field hides what is typed: no dictation there. */
    val password: Boolean = false,
    /** The field's action (EditorInfo.IME_ACTION_*), for the action key's icon. */
    val action: Int = EditorInfo.IME_ACTION_NONE,
    val multiLine: Boolean = false,
)

/**
 * The dictation of the voice keyboard, held by the service and not by its view, so a turn of the screen or a view built
 * again never stops it (Transcribro issue 85, https://github.com/soupslurpr/Transcribro/issues/85). The pattern is our
 * Keyboard's DictationController (a fork of https://github.com/HeliBorg/HeliBoard, app/src/main/java/helium314/keyboard/
 * latin/dictation/DictationController.kt): the engine is chosen when listening starts, partial words sit in the field as
 * composing text and are replaced at each partial result, a finished phrase is committed in their place, and
 * everything is released when it ends; the options (auto start, auto stop, auto send, back to the previous keyboard)
 * are Transcribro's (VoiceInput.kt, https://github.com/soupslurpr/Transcribro) and the text around the words is
 * Insertion's. The prompt is the last 200 characters before the cursor (Transcribro issue 44,
 * https://github.com/soupslurpr/Transcribro/issues/44). Dictation is used on the main thread, as its notes say.
 */
class ImeDictation(
    private val context: Context,
    private val speech: VoiceSpeech,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    /** The field's connection now, null when there is none. */
    private val connection: () -> InputConnection?,
    /** Gives the keyboard back to the one used before (InputMethodService.switchToPreviousInputMethod). */
    private val switchBack: () -> Unit,
) : Dictation.Listener {

    private val dictation = Dictation(context.applicationContext)
    private val _state = MutableStateFlow(ImeState())
    val state: StateFlow<ImeState> = _state.asStateFlow()

    private var info: EditorInfo? = null
    private var starting: Job? = null

    /** Words may still arrive (a tap on stop lets the last phrase be written out first). */
    private var accepting = false
    private var composing = false
    private var wrote = false

    /** The text around the place the words go, taken when the words of a phrase begin and kept until it is committed. */
    private var before = ""
    private var selected: String? = null

    private val phase: Phase get() = _state.value.heard.phase

    /** A field takes the keyboard: listening starts if asked to, but only for a new field and never in a password. */
    fun fieldStarted(editor: EditorInfo, restarting: Boolean) {
        info = editor
        val password = Insertion.isPassword(editor.inputType)
        _state.update {
            it.copy(
                password = password,
                action = FieldKeys.action(editor),
                multiLine = FieldKeys.multiLine(editor),
                problem = if (it.problem == Problem.MICROPHONE && granted()) null else it.problem,
            )
        }
        if (password) {
            stop(flush = false)
            return
        }
        if (!restarting && store.current.imeAutoStart && phase == Phase.IDLE) start()
    }

    /** The field or the keyboard is gone: nothing is kept, the words held are dropped. */
    fun fieldFinished() {
        stop(flush = false)
        info = null
    }

    /** The service ends: nothing of the engines stays. */
    fun release() {
        stop(flush = false)
    }

    /** The mic: ends listening (the words held are written out), or starts it. */
    fun mic() {
        when (phase) {
            Phase.LISTENING -> stop(flush = true)
            Phase.IDLE -> start()
            else -> Unit
        }
    }

    /** The keyboard button: stops without writing out what is held and gives the previous keyboard back. */
    fun leave() {
        stop(flush = false)
        switchBack()
    }

    /** A key acts on the field: the words so far stay as written and the next ones begin anew. */
    fun settle() {
        finishComposing()
    }

    private fun granted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (_state.value.password || phase != Phase.IDLE || dictation.busy) return
        if (!granted()) {
            _state.update { it.copy(problem = Problem.MICROPHONE, heard = it.heard.copy(note = noteFor(Dictation.Why.NO_MICROPHONE_RIGHT))) }
            return
        }
        _state.update { it.copy(problem = null, heard = Heard(phase = Phase.STARTING)) }
        starting = scope.launch {
            // Debug builds may hear a recorded file instead of the microphone (TestSound), release builds never
            val sound = com.yaz.language.recognize.TestSound.forKeyboard(context)
            val engine = if (sound != null) Dictation.Engine.WHISPER else speech.engine()
            if (engine == null) {
                _state.update { it.copy(problem = Problem.ENGINE, heard = it.heard.copy(phase = Phase.IDLE, note = "No speech engine yet")) }
                return@launch
            }
            // Transcribro issue 44 (the words before the cursor as whisper's prompt) was tried on 2026-10-18 and left
            // out: after "Hello" whisper wrote the jfk sample without its capitals and stops, copying the prompt's style
            val prompt: String? = null
            val language = info?.hintLocales?.takeIf { !it.isEmpty }?.get(0) ?: speech.language()
            val silence = if (store.current.imeAutoStop) Dictation.SILENCE_MS else NO_AUTO_STOP_MS
            accepting = true
            composing = false
            wrote = false
            _state.update { it.copy(heard = it.heard.copy(phase = Phase.LISTENING, whisper = engine == Dictation.Engine.WHISPER)) }
            if (!dictation.start(engine, language, speech.model.file, this@ImeDictation, source = sound?.invoke(), silenceMs = silence, prompt = prompt, shortContext = speech.model.pin.shortContext)) {
                accepting = false
                _state.update { it.copy(heard = it.heard.copy(phase = Phase.IDLE)) }
            }
        }
    }

    /**
     * Ends listening. With [flush] the words still held are written out first (the user tapped the mic); without,
     * they are dropped and nothing more is reported (the field or the keyboard was left).
     */
    fun stop(flush: Boolean) {
        val now = phase
        if (now == Phase.IDLE || now == Phase.STOPPING) return
        if (flush && now == Phase.LISTENING) {
            _state.update { it.copy(heard = it.heard.copy(phase = Phase.STOPPING)) }
            dictation.stop(true)
        } else {
            starting?.cancel()
            accepting = false
            dictation.stop(false)
            finishComposing()
            _state.update { it.copy(heard = it.heard.copy(phase = Phase.IDLE, partial = "", level = 0f)) }
        }
    }

    override fun partial(text: String) {
        if (!accepting) return
        val ic = connection() ?: return
        if (!composing) look(ic)
        ic.beginBatchEdit()
        ic.setComposingText(fit(text), 1)
        ic.endBatchEdit()
    }

    override fun committed(text: String) {
        if (!accepting) return
        val ic = connection() ?: return
        if (!composing) look(ic)
        val words = fit(text)
        composing = false
        ic.beginBatchEdit()
        ic.commitText(words, 1)
        ic.endBatchEdit()
        if (words.isNotBlank()) wrote = true
    }

    override fun stopped(why: Dictation.Why) {
        accepting = false
        finishComposing()
        val handed = wrote && (why == Dictation.Why.USER || why == Dictation.Why.SILENCE)
        _state.update {
            it.copy(
                problem = if (why == Dictation.Why.NO_MICROPHONE_RIGHT) Problem.MICROPHONE else it.problem,
                heard = it.heard.copy(
                    phase = Phase.IDLE, partial = "", level = 0f,
                    note = noteFor(why) ?: if (!wrote && why != Dictation.Why.USER) "Nothing heard" else null,
                ),
            )
        }
        if (why != Dictation.Why.USER && why != Dictation.Why.SILENCE) return
        // The words are in: sent if the field's action is Send and asked for, then the previous keyboard comes back.
        if (handed && store.current.imeAutoSend && _state.value.action == EditorInfo.IME_ACTION_SEND) {
            connection()?.performEditorAction(EditorInfo.IME_ACTION_SEND)
        }
        if (store.current.imeSwitchBack) switchBack()
    }

    // Android's onRmsChanged passed on by the kit
    // https://developer.android.com/reference/android/speech/RecognitionListener#onRmsChanged(float)
    override fun level(rmsDb: Float) = _state.update {
        if (it.heard.phase == Phase.LISTENING) it.copy(heard = it.heard.copy(level = rmsDb)) else it
    }

    /** What is around the place the words go, kept while a phrase is composed (a selection is gone after the first words). */
    private fun look(ic: InputConnection) {
        before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        selected = ic.getSelectedText(0)?.toString()
        composing = true
    }

    private fun fit(text: String): String = Insertion.words(
        text, before, selected,
        spaces = Insertion.languageHasSpaces((info?.hintLocales?.takeIf { !it.isEmpty }?.get(0) ?: speech.language()).language),
    )

    private fun finishComposing() {
        if (composing) {
            composing = false
            connection()?.finishComposingText()
        }
    }

    companion object {
        /** Silence never ends the dictation: ten minutes, and the user ends it with the mic. */
        private const val NO_AUTO_STOP_MS = 10 * 60 * 1000L
    }
}
