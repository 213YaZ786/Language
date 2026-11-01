package com.yaz.language.feature.home

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/*
 * Ported from offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0,
 * ui/components/ActionButtons.kt (pasteFromClipboard, rememberClipboardHasText, clipboardHasText):
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/ui/components/ActionButtons.kt
 * Cut: the web address that opens offline-translator's browser and the unsupported document toast (no browser).
 */

/** The first item of the clipboard as text, or null when there is none. */
fun pastedText(context: Context): String? {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = clipboardManager.primaryClip ?: return null
    if (clipData.itemCount == 0) return null
    return clipData.getItemAt(0).text?.toString()
}

/** Puts [text] on the clipboard. */
fun copyText(context: Context, text: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText("Language", text))
}

// Tracks whether the clipboard currently holds pasteable text, so a paste
// button can be disabled when there's nothing to paste. Only reads the clip
// description (MIME types), never the content, so it never trips the
// Android 12+ clipboard-access toast.
@Composable
fun rememberClipboardHasText(): Boolean {
    val context = LocalContext.current
    val view = LocalView.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var hasText by remember { mutableStateOf(clipboardManager.clipboardHasText()) }

    DisposableEffect(clipboardManager, view) {
        val refresh = { hasText = clipboardManager.clipboardHasText() }
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        // Clipboard reads are blocked until the window regains focus (Android 10+),
        // and a copy done in another app while we were backgrounded doesn't deliver
        // a clip-changed event. Re-read on focus gain, when access is permitted.
        val focusListener =
            ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    refresh()
                }
            }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            clipboardManager.removePrimaryClipChangedListener(clipListener)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        }
    }
    return hasText
}

private fun ClipboardManager.clipboardHasText(): Boolean {
    if (!hasPrimaryClip()) return false
    val description = primaryClipDescription ?: return false
    return description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
}
