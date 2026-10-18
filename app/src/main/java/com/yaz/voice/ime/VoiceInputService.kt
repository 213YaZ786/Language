package com.yaz.voice.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.yaz.voice.MainActivity
import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.inject

/**
 * Voice's voice keyboard: an input method that only dictates. Ported from Transcribro's VoiceInput service
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/voiceinput/VoiceInput.kt
 * (a ComposeView as the input view, the lifecycle, saved state and view model store owners set on the window's decor
 * view, listening started when the keyboard shows and stopped when the field is left, the previous keyboard given back
 * with switchToPreviousInputMethod). The dictation is held here, in ImeDictation, not in the view: a turn of the
 * screen or an input view built again never stops it (Transcribro issue 85,
 * https://github.com/soupslurpr/Transcribro/issues/85). The panel is VoicePanel.
 * https://developer.android.com/reference/android/inputmethodservice/InputMethodService
 */
class VoiceInputService : InputMethodService() {

    private val owner = ImeLifecycleOwner()
    private val speech: VoiceSpeech by inject()
    private val store: SettingsStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dictation: ImeDictation

    override fun onCreate() {
        super.onCreate()
        owner.onCreate()
        dictation = ImeDictation(
            context = this,
            speech = speech,
            store = store,
            scope = scope,
            connection = { currentInputConnection },
            // No keyboard in the history (Voice chosen from Settings or by adb): the next one, as Android's own
            // switching key does (InputMethodService.switchToNextInputMethod,
            // https://developer.android.com/reference/android/inputmethodservice/InputMethodService#switchToNextInputMethod(boolean))
            switchBack = { if (!switchToPreviousInputMethod()) switchToNextInputMethod(false) },
        )
    }

    override fun onCreateInputView(): View {
        owner.attachToDecorView(window?.window?.decorView)
        return ComposeView(this).apply {
            setContent {
                val state by dictation.state.collectAsState()
                VoicePanel(
                    state = state,
                    keys = PanelKeys(
                        onMic = dictation::mic,
                        onSettings = ::openVoice,
                        onKeyboard = dictation::leave,
                        onUndo = { onField(FieldKeys::undo) },
                        onRedo = { onField(FieldKeys::redo) },
                        onBackspace = { onField(FieldKeys::backspace) },
                        onAction = { onField { FieldKeys.performAction(it, currentInputEditorInfo) } },
                        onReturn = { onField { FieldKeys.returnKey(it, currentInputEditorInfo) } },
                        onClearUnselected = { onField(FieldKeys::clearUnselected) },
                        onProblem = ::openVoice,
                    )
                )
            }
        }
    }

    /** The field stays in view in landscape too: no full screen editor over it (InputMethodService.onEvaluateFullscreenMode). */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** A key of the panel acts on the field; the words dictated so far stay as written, the next ones begin anew. */
    private fun onField(act: (InputConnection) -> Unit) {
        dictation.settle()
        currentInputConnection?.let(act)
    }

    /** Settings and the problems' buttons: Voice opens, listening stops (Transcribro cancels before opening its settings). */
    private fun openVoice() {
        dictation.stop(flush = false)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        owner.onResume()
        if (editorInfo != null) dictation.fieldStarted(editorInfo, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dictation.fieldFinished()
        owner.onPause()
    }

    override fun onFinishInput() {
        dictation.fieldFinished()
        super.onFinishInput()
    }

    override fun onDestroy() {
        dictation.release()
        scope.cancel()
        super.onDestroy()
        owner.onDestroy()
    }
}
