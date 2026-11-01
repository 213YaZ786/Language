/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/ProcessTextActivity.kt
 *
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Kept: the reading of the incoming text (PROCESS_TEXT: EXTRA_PROCESS_TEXT, else EXTRA_TEXT, as a CharSequence; SEND:
 * EXTRA_TEXT), the read-only test (EXTRA_PROCESS_TEXT_READONLY, true when missing) and the reply (EXTRA_PROCESS_TEXT
 * in the result). Cut: TRANSLATE and OPEN_POPUP, a shared link, document or stream, the viewmodel and its theme, and
 * every Log (upstream logged the text; OWASP MASVS-STORAGE-2). Added: text capped at 50 000 characters and never kept;
 * the panel after this app's recognize/RecognizeActivity (translucent window, sheet at the bottom, tap outside
 * cancels, glass); iOS's Translate sheet actions (Copy Translation, Replace with Translation, Open in Translate) with
 * Listen and Share.
 *
 * Listen is Android's TextToSpeech client
 * https://developer.android.com/reference/android/speech/tts/TextToSpeech (upstream's SystemTtsText.kt is the SSML
 * reader of its own speech service, not a client; nothing of it is needed to read plain text).
 * Share excludes this panel from the chooser with Intent.EXTRA_EXCLUDE_COMPONENTS
 * https://developer.android.com/reference/android/content/Intent#EXTRA_EXCLUDE_COMPONENTS
 */
package com.yaz.language.processtext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.yaz.language.MainActivity
import com.yaz.language.R
import com.yaz.language.translate.LanguageStateManager
import com.yaz.language.translate.TranslationCoordinator
import com.yaz.language.translate.TranslationResult
import com.yaz.language.translate.aidl.TranslateGuards
import com.yaz.language.translate.localizedName
import com.yaz.language.ui.component.BoldButton
import com.yaz.language.ui.component.ButtonGap
import com.yaz.language.ui.component.ZoneSurface
import com.yaz.language.ui.component.rememberHaptics
import com.yaz.language.ui.glass.LocalGlass
import com.yaz.language.ui.glass.rememberGlassLook
import com.yaz.language.ui.theme.AppTheme
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** What the panel shows. Only [Done] carries text, and it lives in memory. */
internal sealed interface Ui {
    data object Working : Ui

    /** [from] and [to] are the languages' names; [toCode] is the target's catalogue code, for the voice. */
    data class Done(val from: String, val to: String, val toCode: String, val translated: String, val source: String) : Ui

    /** A language to get before this text can be translated. */
    data class Missing(val name: String, val code: String) : Ui

    data object NotRecognised : Ui

    data object Failed : Ui
}

class ProcessTextActivity : ComponentActivity() {

    private val coordinator: TranslationCoordinator by inject()
    private val states: LanguageStateManager by inject()
    private val shown = MutableTransitionState(false)
    private var closing by mutableStateOf(false)
    private var ui by mutableStateOf<Ui>(Ui.Working)
    private var note by mutableStateOf<String?>(null)
    private var clipped = false
    private var replaceable = false
    private var voice: TextToSpeech? = null
    private var voiceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val clip = TranslateGuards.clip(incomingText())
        if (clip == null) {
            finish()
            return
        }
        clipped = clip.clipped
        // Upstream: READONLY is true when the extra is missing.
        replaceable = TranslateGuards.canReplace(
            intent.action,
            runCatching { intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) }.getOrDefault(true)
        )
        setContent { Panel() }
        // The task survives a turn of the screen (configChanges), so the work starts once.
        if (savedInstanceState == null) lifecycleScope.launch { ui = translate(clip.text) }
    }

    // The panel is no longer in front: nothing keeps speaking, no engine stays bound.
    override fun onStop() {
        releaseVoice()
        super.onStop()
    }

    override fun onDestroy() {
        releaseVoice()
        super.onDestroy()
    }

    /**
     * The incoming text as upstream reads it: PROCESS_TEXT carries EXTRA_PROCESS_TEXT (EXTRA_TEXT when absent), SEND
     * carries EXTRA_TEXT. A malformed extra reads as nothing.
     */
    private fun incomingText(): CharSequence? = runCatching {
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                if (intent.hasExtra(Intent.EXTRA_PROCESS_TEXT)) intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }
    }.getOrNull()

    /**
     * Finds the language among all of the catalogue (a text in a language that is not installed is named, not
     * mistaken for an installed one), then the target (the phone's language, then English), and translates when the
     * pair is installed. A missing language is named for the Models screen.
     */
    private suspend fun translate(text: String): Ui {
        val catalog = states.catalog.value
        val from = coordinator.detectLanguageRobust(text, null, catalog.languageList) ?: return Ui.NotRecognised
        val availability = states.languageState.value
        if (!availability.canBeSource(from)) return Ui.Missing(from.localizedName(), from.code)

        val preferred = listOf(TranslateGuards.codeOf(Locale.getDefault()), "en")
        val toCode = TranslateGuards.pickTarget(preferred, availability.targetLanguages().map { it.code }, from.code)
        if (toCode == null) {
            val wanted = preferred.firstOrNull { it != from.code && catalog.languageByCode(it) != null } ?: return Ui.Failed
            val language = catalog.languageByCode(wanted) ?: return Ui.Failed
            return Ui.Missing(language.localizedName(), language.code)
        }
        val to = catalog.languageByCode(toCode) ?: return Ui.Failed
        if (!states.canTranslate(from, to)) return Ui.Missing(to.localizedName(), to.code)

        return when (val result = coordinator.translateText(from, to, text)) {
            is TranslationResult.Success ->
                Ui.Done(from.localizedName(), to.localizedName(), to.code, result.result.translated, text)
            is TranslationResult.Error -> Ui.Failed
        }
    }

    private fun copy(done: Ui.Done) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(null, done.translated))
        note = getString(R.string.process_copied)
    }

    /** Hands the translation back to the app that selected the text (iOS: Replace with Translation). */
    private fun replace(done: Ui.Done) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, done.translated))
        close()
    }

    private fun share(done: Ui.Done) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, done.translated)
        val chooser = Intent.createChooser(send, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ProcessTextActivity::class.java)))
        runCatching { startActivity(chooser) }
    }

    /** Android's TextToSpeech: the engine is bound on the first tap and released when the panel leaves the front. */
    private fun listen(done: Ui.Done) {
        val engine = voice
        if (engine != null) {
            if (voiceReady) speak(engine, done)
            return
        }
        voice = TextToSpeech(this) { status ->
            val current = ui as? Ui.Done
            val made = voice
            if (status == TextToSpeech.SUCCESS && made != null && current != null) {
                voiceReady = true
                speak(made, current)
            } else {
                note = getString(R.string.process_voice_failed)
                releaseVoice()
            }
        }
    }

    private fun speak(engine: TextToSpeech, done: Ui.Done) {
        val locale = Locale.forLanguageTag(TranslateGuards.tagOf(done.toCode))
        val available = engine.setLanguage(locale)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            note = getString(R.string.process_no_voice, done.to)
            return
        }
        // speak() refuses more than this; a longer text is told, not cut.
        if (done.translated.length > TextToSpeech.getMaxSpeechInputLength()) {
            note = getString(R.string.process_too_long_aloud)
            return
        }
        note = null
        if (engine.speak(done.translated, TextToSpeech.QUEUE_FLUSH, null, "translation") == TextToSpeech.ERROR) {
            note = getString(R.string.process_voice_failed)
        }
    }

    private fun releaseVoice() {
        voice?.run {
            stop()
            shutdown()
        }
        voice = null
        voiceReady = false
    }

    /** Language's own screens: Models for a missing language, else the app. */
    private fun openLanguage(modelsFor: String?) {
        val open = Intent(this, MainActivity::class.java)
        if (modelsFor != null) {
            open.putExtra(TranslateGuards.EXTRA_OPEN_MODELS, true)
            open.putExtra(TranslateGuards.EXTRA_MODELS_LANGUAGE, modelsFor)
        }
        startActivity(open)
        cancel()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        close()
    }

    /** The sheet slides down, then the activity ends (Panel). */
    private fun close() {
        closing = true
        shown.targetState = false
    }

    /**
     * The window of the panel, as RecognizeActivity's: the app's theme and glass over a window that stays see-through,
     * bars transparent.
     */
    @Composable
    private fun Panel() {
        val dark = isSystemInDarkTheme()
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
            )
            onDispose { }
        }
        AppTheme(darkTheme = dark) {
            val look = rememberGlassLook(MaterialTheme.colorScheme, true)
            CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                val haptics = rememberHaptics()
                LaunchedEffect(Unit) { shown.targetState = true }
                LaunchedEffect(closing, shown.currentState, shown.targetState) {
                    if (closing && !shown.currentState && !shown.targetState) finish()
                }
                BackHandler(enabled = !closing, onBack = ::cancel)
                // A feel for each tap, then the action, unless the sheet is already leaving (as RecognizeActivity's onMic).
                PanelSheet(
                    shown = shown,
                    state = ui,
                    note = note,
                    clipped = clipped,
                    canReplace = replaceable,
                    onCopy = { done -> if (!closing) { haptics.tick(); copy(done) } },
                    onReplace = { done -> if (!closing) { haptics.tick(); replace(done) } },
                    onShare = { done -> if (!closing) { haptics.tick(); share(done) } },
                    onListen = { done -> if (!closing) { haptics.tick(); listen(done) } },
                    onOpen = { modelsFor -> if (!closing) { haptics.tick(); openLanguage(modelsFor) } },
                    onCancel = { if (!closing) cancel() },
                )
            }
        }
    }
}

/**
 * The sheet: a glass pane at the bottom of a dimmed window, over the app that asked (RecognizePanel's layout). A tap
 * outside cancels. Languages and the original are text, not zones (nothing to tap); only the actions are glass buttons.
 */
@Composable
internal fun PanelSheet(
    shown: MutableTransitionState<Boolean>,
    state: Ui,
    note: String?,
    clipped: Boolean,
    canReplace: Boolean,
    onCopy: (Ui.Done) -> Unit,
    onReplace: (Ui.Done) -> Unit,
    onShare: (Ui.Done) -> Unit,
    onListen: (Ui.Done) -> Unit,
    onOpen: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.process_close),
                onClick = onCancel
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            // Taps on the sheet itself are not "outside": they stop here.
            Box(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            ) {
                ZoneSurface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    ) {
                        SheetContent(state, note, clipped, canReplace, onCopy, onReplace, onShare, onListen, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetContent(
    state: Ui,
    note: String?,
    clipped: Boolean,
    canReplace: Boolean,
    onCopy: (Ui.Done) -> Unit,
    onReplace: (Ui.Done) -> Unit,
    onShare: (Ui.Done) -> Unit,
    onListen: (Ui.Done) -> Unit,
    onOpen: (String?) -> Unit,
) {
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    when (state) {
        Ui.Working -> Message(stringResource(R.string.process_working), faint)
        Ui.NotRecognised -> Message(stringResource(R.string.process_not_recognised), MaterialTheme.colorScheme.onSurface)
        Ui.Failed -> Message(stringResource(R.string.process_failed), MaterialTheme.colorScheme.error)
        is Ui.Missing -> {
            Message(stringResource(R.string.process_get_language, state.name), MaterialTheme.colorScheme.onSurface)
            Actions(listOf(Action(stringResource(R.string.process_get_languages)) { onOpen(state.code) }))
        }
        is Ui.Done -> {
            Text(
                stringResource(R.string.process_from_to, state.from, state.to),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The original, short: a minimum of words, never a fixed box.
            Text(
                state.source,
                style = MaterialTheme.typography.bodyMedium,
                color = faint,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val line = MaterialTheme.typography.bodyLarge.lineHeight
            val eightLines = with(LocalDensity.current) { line.toDp() * 8 }
            Box(Modifier.fillMaxWidth().heightIn(max = eightLines).verticalScroll(rememberScrollState())) {
                Text(
                    state.translated,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            val shownNote = note ?: if (clipped) stringResource(R.string.process_clipped) else null
            if (shownNote != null) {
                Text(
                    shownNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            val actions = buildList {
                add(Action(stringResource(R.string.process_copy)) { onCopy(state) })
                if (canReplace) add(Action(stringResource(R.string.process_replace)) { onReplace(state) })
                add(Action(stringResource(R.string.process_share)) { onShare(state) })
                add(Action(stringResource(R.string.process_listen)) { onListen(state) })
                add(Action(stringResource(R.string.process_open)) { onOpen(null) })
            }
            Actions(actions)
        }
    }
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

private class Action(val label: String, val onClick: () -> Unit)

/**
 * Glass buttons two to a row, filling the width; an odd last one takes the whole row. The first is the main action.
 * Words wrap, nothing has a fixed size.
 */
@Composable
private fun Actions(actions: List<Action>) {
    Column(verticalArrangement = Arrangement.spacedBy(ButtonGap), modifier = Modifier.fillMaxWidth()) {
        var first = true
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGap), modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    BoldButton(onClick = action.onClick, filled = first, modifier = Modifier.weight(1f)) {
                        Text(action.label, textAlign = TextAlign.Center)
                    }
                    first = false
                }
            }
        }
    }
}
