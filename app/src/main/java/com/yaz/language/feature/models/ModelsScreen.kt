package com.yaz.language.feature.models

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.language.R
import com.yaz.language.core.VoiceSpeech
import com.yaz.language.core.voice.SpeechModel
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.feature.common.isPhoneVoiceInput
import com.yaz.language.feature.common.isVoiceKeyboardEnabled
import com.yaz.language.feature.common.openKeyboardSettings
import com.yaz.language.feature.common.openVoiceInputSettings
import com.yaz.language.feature.common.rememberMicrophone
import com.yaz.language.navigation.LocalReadableInset
import com.yaz.language.translate.DownloadStatus
import com.yaz.language.translate.Language
import com.yaz.language.translate.inFlightProgress
import com.yaz.language.translate.localizedName
import com.yaz.language.ui.component.BoldButton
import com.yaz.language.ui.component.ChoiceDialog
import com.yaz.language.ui.component.FloatingAction
import com.yaz.language.ui.component.FloatingFrame
import com.yaz.language.ui.component.FloatingTop
import com.yaz.language.ui.component.GlassRadio
import com.yaz.language.ui.component.LinearWavyProgress
import com.yaz.language.ui.component.QuietButton
import com.yaz.language.ui.component.ZoneSurface
import com.yaz.language.ui.component.rememberHaptics
import com.yaz.language.ui.icon.AppIcons
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Models: everything the phone keeps for Language, in two sections. Speech: the microphone, the engine (Base or Small
 * with Get, progress and Remove), the phone's voice input and the voice keyboard, the rows that were on Home, which
 * follow Transcribro's StartScreen (each requirement ticked or with its action)
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/start/StartScreen.kt
 * Languages: Google Translate's "Downloaded languages" list (name, size, delete) with iOS Translate's choice of what
 * to download, as offline-translator's language manager lists them (v0.8.7, commit
 * e7716c6859e3d90b8883c667c1a3cfeef407df08, GPL-3.0, ui/screens/LanguageAssetManagerScreen.kt): Get with its progress
 * and Cancel, Retry after a failure, Delete after a confirmation. Built as Calculator's Settings (FloatingFrame, Section,
 * SettingRow) https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt
 *
 * [focus] is the catalogue code of a language the panel asked for (Google Translate opens "Downloaded languages" on the
 * row of the missing language): its row is scrolled into view and washed for a moment, then [onFocused] is called.
 */
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    focus: String? = null,
    onFocused: () -> Unit = {},
    viewModel: ModelsViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val store: SettingsStore = koinInject()
    val speech: VoiceSpeech = koinInject()
    val settings by store.settings.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val mic = rememberMicrophone()
    val model = speech.model
    val system by model.system.collectAsState()
    val state by model.state.collectAsState()
    // The choice is read from the settings, so a pick redraws the radios.
    @Suppress("UNUSED_VARIABLE") val picked = settings.speechModel

    // The rows are read again each time the screen comes back (from Android's Settings, say).
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        model.recheck()
        onPauseOrDispose { }
    }
    val voiceInput = remember(checks) { isPhoneVoiceInput(context) }
    val voiceKeyboard = remember(checks) { isVoiceKeyboardEnabled(context) }
    val metered = remember { model.metered() }

    ModelsContent(
        speech = SpeechUi(
            micGranted = mic.granted,
            micRefusedForGood = mic.refusedForGood,
            voiceInput = voiceInput,
            voiceKeyboard = voiceKeyboard,
            phoneRecognizer = system,
            usesSystem = model.usesSystem,
            pin = model.pin,
            state = state,
            metered = metered
        ),
        rows = rows,
        networkAllowed = settings.networkAllowed,
        focus = focus,
        actions = ModelsActions(
            onBack = onBack,
            onSettings = onSettings,
            onMic = mic.request,
            onVoiceInput = { openVoiceInputSettings(context) },
            onVoiceKeyboard = { openKeyboardSettings(context) },
            onChooseSystem = model::chooseSystem,
            onChoosePin = model::choose,
            onFetch = model::fetch,
            onCancelFetch = model::cancel,
            onRemove = model::remove,
            onGet = viewModel::get,
            onCancel = viewModel::cancel,
            onDelete = viewModel::delete,
            onFocused = onFocused
        )
    )
}

/** What the Models screen shows of the speech engine and the phone's voice settings. */
internal class SpeechUi(
    val micGranted: Boolean,
    val micRefusedForGood: Boolean,
    val voiceInput: Boolean,
    val voiceKeyboard: Boolean,
    /** The phone has its own recognizer with the language installed. */
    val phoneRecognizer: Boolean,
    val usesSystem: Boolean,
    /** The Whisper model chosen (or recommended). */
    val pin: SpeechModel.Pin,
    val state: SpeechModel.State,
    val metered: Boolean
)

/** What a tap on the Models screen does. */
internal class ModelsActions(
    val onBack: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onMic: () -> Unit = {},
    val onVoiceInput: () -> Unit = {},
    val onVoiceKeyboard: () -> Unit = {},
    val onChooseSystem: () -> Unit = {},
    val onChoosePin: (SpeechModel.Pin) -> Unit = {},
    val onFetch: () -> Unit = {},
    val onCancelFetch: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onGet: (Language) -> Unit = {},
    val onCancel: (Language) -> Unit = {},
    val onDelete: (Language) -> Unit = {},
    val onFocused: () -> Unit = {}
)

/** The screen with its data given, so a test draws it with fixed data. */
@Composable
internal fun ModelsContent(
    speech: SpeechUi,
    rows: List<LanguageRow>,
    networkAllowed: Boolean,
    focus: String?,
    actions: ModelsActions
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()

    // The language to delete, asked once more before it goes.
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    val pageScroll = rememberScrollState()
    FloatingFrame(
        bottom = 0.dp,
        scroll = pageScroll,
        top = { FloatingTop(stringResource(R.string.models_title), leading = { FloatingAction(AppIcons.ArrowBack, stringResource(R.string.back), actions.onBack) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(horizontal = LocalReadableInset.current)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))

            Section(stringResource(R.string.section_speech)) {
                StatusRow(
                    icon = AppIcons.Mic,
                    title = stringResource(R.string.microphone),
                    summary = stringResource(if (speech.micGranted) R.string.allowed else R.string.not_allowed),
                    ok = speech.micGranted,
                    action = stringResource(if (speech.micRefusedForGood) R.string.settings else R.string.allow),
                    onClick = { haptics.tick(); actions.onMic() }
                )
                EngineChoices(speech, networkAllowed, actions)
                StatusRow(
                    icon = AppIcons.Keyboard,
                    title = stringResource(R.string.voice_input),
                    summary = stringResource(if (speech.voiceInput) R.string.voice_input_yes else R.string.voice_input_no),
                    ok = speech.voiceInput,
                    action = stringResource(R.string.choose),
                    onClick = { haptics.tick(); actions.onVoiceInput() }
                )
                StatusRow(
                    icon = AppIcons.Keyboard,
                    title = stringResource(R.string.voice_keyboard),
                    summary = stringResource(if (speech.voiceKeyboard) R.string.turned_on else R.string.not_turned_on),
                    ok = speech.voiceKeyboard,
                    action = stringResource(R.string.activate),
                    onClick = { haptics.tick(); actions.onVoiceKeyboard() }
                )
            }

            Section(stringResource(R.string.section_languages)) {
                rows.forEach { row ->
                    val wanted = focus != null && row.language.code == focus
                    LanguageRowView(
                        row = row,
                        networkAllowed = networkAllowed,
                        focused = wanted,
                        onFocused = actions.onFocused,
                        onGet = { haptics.done(); if (networkAllowed) actions.onGet(row.language) else actions.onSettings() },
                        onCancel = { haptics.tick(); actions.onCancel(row.language) },
                        onDelete = { haptics.tick(); deleting = row.language.code }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    rows.firstOrNull { it.language.code == deleting }?.let { row ->
        val size = Formatter.formatShortFileSize(context, row.bytes)
        ChoiceDialog(
            title = stringResource(R.string.delete_title, row.language.localizedName()),
            text = stringResource(R.string.delete_text, size),
            options = listOf(true to stringResource(R.string.delete)),
            danger = setOf(true),
            onPick = { haptics.reject(); actions.onDelete(row.language) },
            onDismiss = { deleting = null }
        )
    }
}

/**
 * The ways to write speech out: the phone's own recognizer when it has the language (nothing to get), and the two
 * Whisper models, each with its size and what it is good at, a radio on each. Under them, where the chosen model
 * stands with its one action: Download, its progress with Cancel, or on the phone with Remove; with the Network switch
 * off the action leads to Settings. After SMS's ModelChoices and SpeechModelPanel (feature/thread/Voice.kt,
 * https://github.com/213YaZ786/SMS), moved here from Settings.
 */
@Composable
private fun EngineChoices(speech: SpeechUi, networkAllowed: Boolean, actions: ModelsActions) {
    val haptics = rememberHaptics()
    val state = speech.state
    val fetching = state is SpeechModel.State.Fetching

    if (speech.phoneRecognizer) {
        SettingRow(
            title = stringResource(R.string.phone_recognizer),
            summary = stringResource(R.string.nothing_to_get),
            onClick = { if (!fetching) actions.onChooseSystem() },
            trailing = { GlassRadio(selected = speech.usesSystem, onClick = null) }
        )
    }
    listOf(SpeechModel.BASE, SpeechModel.SMALL).forEach { pin ->
        SettingRow(
            title = stringResource(R.string.whisper_name, pin.label),
            // Joined with a comma, as AOSP Dialer's CallLogEntryText.java (https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/java/com/android/dialer/calllogutils/CallLogEntryText.java).
            summary = stringResource(R.string.whisper_summary, (pin.bytes / 1_000_000).toInt(), pin.quality),
            onClick = { if (!fetching) actions.onChoosePin(pin) },
            trailing = { GlassRadio(selected = !speech.usesSystem && speech.pin == pin, onClick = null) }
        )
    }
    if (!speech.usesSystem) {
        val get = { haptics.done(); if (networkAllowed) actions.onFetch() else actions.onSettings() }
        ListItem(
            headlineContent = {
                when (state) {
                    // The fetch drawn as Material 3 Expressive's wavy line, through this app's port of it
                    // https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt#LinearWavyProgressIndicator
                    is SpeechModel.State.Fetching -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearWavyProgress(progress = { state.done }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.getting_percent, (state.done * 100).toInt()))
                    }
                    SpeechModel.State.Ready -> Text(stringResource(R.string.on_the_phone))
                    SpeechModel.State.Failed -> Text(stringResource(R.string.could_not_get), color = MaterialTheme.colorScheme.error)
                    SpeechModel.State.Missing ->
                        if (!networkAllowed) Text(stringResource(R.string.network_off_row), color = MaterialTheme.colorScheme.error)
                        else if (speech.metered) Text(stringResource(R.string.on_mobile_data), color = MaterialTheme.colorScheme.error)
                        else Text(stringResource(R.string.not_on_the_phone))
                }
            },
            trailingContent = {
                when (state) {
                    is SpeechModel.State.Fetching -> QuietButton(onClick = { haptics.tick(); actions.onCancelFetch() }) { Text(stringResource(R.string.cancel)) }
                    SpeechModel.State.Ready -> QuietButton(onClick = { haptics.reject(); actions.onRemove() }) { Text(stringResource(R.string.remove)) }
                    SpeechModel.State.Missing, SpeechModel.State.Failed -> BoldButton(onClick = get) {
                        Text(
                            when {
                                !networkAllowed -> stringResource(R.string.settings)
                                state == SpeechModel.State.Failed -> stringResource(R.string.retry)
                                else -> stringResource(R.string.get_size, stringResource(R.string.size_mb, (speech.pin.bytes / 1_000_000).toInt()))
                            }
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

/**
 * One language of the catalogue: its name, then its state in words (size, progress, failure, the network off), and the
 * one action that state allows, offline-translator's LanguageDownloadButton as words in glass buttons: Get, the
 * progress with Cancel, Retry, or Delete once it is on the phone. English has no pack, it is the pivot of every pair.
 * A [focused] row (the panel asked for this language) is brought into view with BringIntoViewRequester, Compose's way
 * to scroll a child of a scrolling column to the screen
 * https://developer.android.com/develop/ui/compose/touch-input/pointer-input/scroll#bring-into-view
 * and washed with the accent for two seconds; [onFocused] tells the caller the request was taken.
 */
@Composable
private fun LanguageRowView(
    row: LanguageRow,
    networkAllowed: Boolean,
    focused: Boolean,
    onFocused: () -> Unit,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val progress = row.status.inFlightProgress
    val failed = row.status is DownloadStatus.Failed
    val size = Formatter.formatShortFileSize(context, row.bytes)
    val english = row.language.isEnglish

    val requester = remember { BringIntoViewRequester() }
    var lit by remember { mutableStateOf(false) }
    // The request is consumed as soon as the row is in view, so leaving and coming back does not wash it again.
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        lit = true
        requester.bringIntoView()
        onFocused()
    }
    LaunchedEffect(lit) {
        if (lit) {
            delay(WASH_MILLIS)
            lit = false
        }
    }
    val wash by animateFloatAsState(if (lit) 0.22f else 0f, tween(600), label = "wash")

    ListItem(
        headlineContent = { Text(row.language.localizedName(), color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = {
            val error = failed || (!row.installed && progress == null && !networkAllowed)
            val summary = when {
                english -> stringResource(R.string.english_pivot)
                progress != null -> stringResource(R.string.getting_percent, (progress * 100).toInt())
                failed -> stringResource(R.string.could_not_get)
                row.installed -> stringResource(R.string.on_the_phone_size, size)
                !networkAllowed -> stringResource(R.string.network_off_row)
                else -> size
            }
            // A language pack's download drawn as Material 3 Expressive's wavy line across the row, through this app's
            // port of it, the percentage above it
            // https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt#LinearWavyProgressIndicator
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(summary, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null && !english) LinearWavyProgress(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        trailingContent = if (english) null else ({
            when {
                progress != null -> QuietButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                row.installed -> QuietButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                else -> BoldButton(onClick = onGet) {
                    Text(stringResource(if (failed) R.string.retry else if (networkAllowed) R.string.get else R.string.settings))
                }
            }
        }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .bringIntoViewRequester(requester)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = wash), MaterialTheme.shapes.large)
    )
}

/** How long the row the panel asked for stays washed. */
private const val WASH_MILLIS = 2000L

/**
 * One requirement: ticked when done, else its icon and an action; the whole row is the target. A list item on a
 * transparent surface as Calculator's SettingRow, since a tap target inside a zone is never a second ZoneSurface.
 * Moved from Home with the rows.
 */
@Composable
private fun StatusRow(icon: ImageVector, title: String, summary: String, ok: Boolean, action: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                if (ok) AppIcons.CheckCircle else icon,
                contentDescription = if (ok) stringResource(R.string.done) else null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = { Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = if (!ok && action != null) ({ BoldButton(onClick = onClick) { Text(action) } }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick)
    )
}

/**
 * A titled group of rows on one rounded zone, moved here from Settings (which is tiles now). Copied from Calculator's
 * SettingsScreen (Section); the zone's corners are Material 3's ExtraLarge, the role of a pane on the page.
 */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
    ZoneSurface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(content = content)
    }
}

/** A row of a section, moved here from Settings. Copied from Calculator's SettingsScreen (SettingRow). */
@Composable
private fun SettingRow(title: String, summary: String?, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    val haptics = rememberHaptics()
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = summary?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable { haptics.tick(); onClick() } else Modifier
    )
}
