package com.yaz.language.feature.models

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.yaz.language.feature.settings.Section
import com.yaz.language.feature.settings.SettingRow
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
import com.yaz.language.ui.component.LoadingMark
import com.yaz.language.ui.component.QuietButton
import com.yaz.language.ui.component.rememberHaptics
import com.yaz.language.ui.icon.AppIcons
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
 * and Cancel, Retry after a failure, Delete after a confirmation. Built as Settings (FloatingFrame, Section, SettingRow).
 */
@Composable
fun ModelsScreen(onBack: () -> Unit, onSettings: () -> Unit, viewModel: ModelsViewModel = koinViewModel()) {
    val context = LocalContext.current
    val store: SettingsStore = koinInject()
    val speech: VoiceSpeech = koinInject()
    val settings by store.settings.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val haptics = rememberHaptics()
    val mic = rememberMicrophone()
    val model = speech.model
    @Suppress("UNUSED_VARIABLE") val system by model.system.collectAsState()

    // The rows are read again each time the screen comes back (from Android's Settings, say).
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        model.recheck()
        onPauseOrDispose { }
    }
    val voiceInput = remember(checks) { isPhoneVoiceInput(context) }
    val voiceKeyboard = remember(checks) { isVoiceKeyboardEnabled(context) }

    // The language to delete, asked once more before it goes.
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    val pageScroll = rememberScrollState()
    FloatingFrame(
        bottom = 0.dp,
        scroll = pageScroll,
        top = { FloatingTop(stringResource(R.string.models_title), leading = { FloatingAction(AppIcons.ArrowBack, stringResource(R.string.back), onBack) }) }
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
                    summary = stringResource(if (mic.granted) R.string.allowed else R.string.not_allowed),
                    ok = mic.granted,
                    action = stringResource(if (mic.refusedForGood) R.string.settings else R.string.allow),
                    onClick = { haptics.tick(); mic.request() }
                )
                EngineChoices(model, settings.speechModel, settings.networkAllowed, onSettings)
                StatusRow(
                    icon = AppIcons.Keyboard,
                    title = stringResource(R.string.voice_input),
                    summary = stringResource(if (voiceInput) R.string.voice_input_yes else R.string.voice_input_no),
                    ok = voiceInput,
                    action = stringResource(R.string.choose),
                    onClick = { haptics.tick(); openVoiceInputSettings(context) }
                )
                StatusRow(
                    icon = AppIcons.Keyboard,
                    title = stringResource(R.string.voice_keyboard),
                    summary = stringResource(if (voiceKeyboard) R.string.turned_on else R.string.not_turned_on),
                    ok = voiceKeyboard,
                    action = stringResource(R.string.activate),
                    onClick = { haptics.tick(); openKeyboardSettings(context) }
                )
            }

            Section(stringResource(R.string.section_languages)) {
                rows.forEach { row ->
                    LanguageRowView(
                        row = row,
                        networkAllowed = settings.networkAllowed,
                        onGet = { haptics.done(); if (settings.networkAllowed) viewModel.get(row.language) else onSettings() },
                        onCancel = { haptics.tick(); viewModel.cancel(row.language) },
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
            onPick = { haptics.reject(); viewModel.delete(row.language) },
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
private fun EngineChoices(model: SpeechModel, chosen: String, networkAllowed: Boolean, onSettings: () -> Unit) {
    val haptics = rememberHaptics()
    val state by model.state.collectAsState()
    val system by model.system.collectAsState()
    // The choice is read from the settings, so a pick redraws the radios.
    @Suppress("UNUSED_VARIABLE") val picked = chosen
    val fetching = state is SpeechModel.State.Fetching
    val metered = remember { model.metered() }

    if (system) {
        SettingRow(
            title = "Phone recognizer",
            summary = "Nothing to get",
            onClick = { if (!fetching) model.chooseSystem() },
            trailing = { GlassRadio(selected = model.usesSystem, onClick = null) }
        )
    }
    listOf(SpeechModel.BASE, SpeechModel.SMALL).forEach { pin ->
        SettingRow(
            title = "Whisper ${pin.label}",
            // Joined with a comma, as AOSP Dialer's CallLogEntryText.java (https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/java/com/android/dialer/calllogutils/CallLogEntryText.java).
            summary = "${pin.bytes / 1_000_000} MB, ${pin.quality}",
            onClick = { if (!fetching) model.choose(pin) },
            trailing = { GlassRadio(selected = !model.usesSystem && model.pin == pin, onClick = null) }
        )
    }
    if (!model.usesSystem) {
        val get = { haptics.done(); if (networkAllowed) model.fetch() else onSettings() }
        ListItem(
            headlineContent = {
                when (val s = state) {
                    is SpeechModel.State.Fetching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LoadingMark(size = 28.dp, progress = s.done)
                        Text(stringResource(R.string.getting_percent, (s.done * 100).toInt()))
                    }
                    SpeechModel.State.Ready -> Text("On the phone")
                    SpeechModel.State.Failed -> Text(stringResource(R.string.could_not_get), color = MaterialTheme.colorScheme.error)
                    SpeechModel.State.Missing ->
                        if (!networkAllowed) Text(stringResource(R.string.network_off_row), color = MaterialTheme.colorScheme.error)
                        else if (metered) Text("On mobile data", color = MaterialTheme.colorScheme.error)
                        else Text("Not on the phone")
                }
            },
            trailingContent = {
                when (state) {
                    is SpeechModel.State.Fetching -> QuietButton(onClick = { haptics.tick(); model.cancel() }) { Text(stringResource(R.string.cancel)) }
                    SpeechModel.State.Ready -> QuietButton(onClick = { haptics.reject(); model.remove() }) { Text("Remove") }
                    SpeechModel.State.Missing, SpeechModel.State.Failed -> BoldButton(onClick = get) {
                        Text(
                            when {
                                !networkAllowed -> stringResource(R.string.settings)
                                state == SpeechModel.State.Failed -> stringResource(R.string.retry)
                                else -> stringResource(R.string.get_size, "${model.pin.bytes / 1_000_000} MB")
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
 */
@Composable
private fun LanguageRowView(row: LanguageRow, networkAllowed: Boolean, onGet: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val progress = row.status.inFlightProgress
    val failed = row.status is DownloadStatus.Failed
    val size = Formatter.formatShortFileSize(context, row.bytes)
    val english = row.language.isEnglish
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
            Text(summary, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = if (english) null else ({
            when {
                progress != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadingMark(size = 24.dp, progress = progress)
                    QuietButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                row.installed -> QuietButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                else -> BoldButton(onClick = onGet) {
                    Text(stringResource(if (failed) R.string.retry else if (networkAllowed) R.string.get else R.string.settings))
                }
            }
        }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

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
