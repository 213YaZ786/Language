package com.yaz.voice.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * What the panel's keys do to the field. Each is Transcribro's, from its VoiceInput screen
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/voiceinput/VoiceInput.kt
 * (undo and redo are Ctrl+Z and Ctrl+Shift+Z key events, backspace deletes the selection or one character, "Clear
 * Unselected" deletes everything around the selection, return is an Enter key event, the action key performs the
 * editor's action), with the action read as Android's own input method service reads it (the action id when the field
 * names its own, else the action bits of imeOptions)
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/inputmethodservice/InputMethodService.java
 */
object FieldKeys {

    /** Ctrl+Z, down then up, as Transcribro sends it. */
    fun undo(ic: InputConnection) = chord(ic, KeyEvent.META_CTRL_ON)

    /** Ctrl+Shift+Z. */
    fun redo(ic: InputConnection) = chord(ic, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)

    private fun chord(ic: InputConnection, downMeta: Int) {
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, downMeta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, 0))
    }

    /** Deletes the selection, or the character before the cursor. */
    fun backspace(ic: InputConnection) {
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
    }

    /** Deletes all the text around the selection (the whole field when nothing is selected). */
    fun clearUnselected(ic: InputConnection) {
        val length = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: return
        val before = ic.getTextBeforeCursor(length, 0) ?: return
        val after = ic.getTextAfterCursor(length, 0) ?: return
        ic.deleteSurroundingText(before.length, after.length)
    }

    /** The Enter key, down then up. */
    fun enter(ic: InputConnection) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    /** The field's action: the one it names itself with a label, else the action bits of its ime options. */
    fun action(info: EditorInfo?): Int {
        if (info == null) return EditorInfo.IME_ACTION_NONE
        return if (info.actionLabel != null && info.actionId != 0) info.actionId else info.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    /** A field that takes several lines: Return writes a new line there. */
    fun multiLine(info: EditorInfo?): Boolean =
        info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    /** The action key: performs the field's action; a field with none (or leaving the choice to the keyboard) is done. */
    fun performAction(ic: InputConnection, info: EditorInfo?) {
        val action = action(info)
        if (action == EditorInfo.IME_ACTION_NONE) return
        ic.performEditorAction(if (action == EditorInfo.IME_ACTION_UNSPECIFIED) EditorInfo.IME_ACTION_DONE else action)
    }

    /** Return: a new line in a multi-line field or one with no action, else the field's action. */
    fun returnKey(ic: InputConnection, info: EditorInfo?) {
        val action = action(info)
        if (multiLine(info) || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) enter(ic)
        else ic.performEditorAction(action)
    }
}
