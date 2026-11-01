package com.yaz.language.feature.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaz.language.BuildConfig
import com.yaz.language.R
import com.yaz.language.core.update.UpdateMode
import com.yaz.language.core.update.Updates
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.navigation.LocalReadableInset
import com.yaz.language.ui.component.FloatingAction
import com.yaz.language.ui.component.FloatingFrame
import com.yaz.language.ui.component.FloatingTop
import com.yaz.language.ui.component.GlassRadio
import com.yaz.language.ui.component.GlassSwitch
import com.yaz.language.ui.component.QuietButton
import com.yaz.language.ui.component.ZoneAlertDialog
import com.yaz.language.ui.component.ZoneSurface
import com.yaz.language.ui.component.rememberHaptics
import com.yaz.language.ui.icon.AppIcons
import org.koin.compose.koinInject

/**
 * Settings, built as Calculator's SettingsScreen (FloatingFrame under a title pill, a titled Section per group on one
 * ZoneSurface, SettingRow and SwitchRow on ListItem, a ChoiceDialog with GlassRadio)
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt
 * The engine choices and their Download, Cancel and Remove follow SMS's speech model panel (ModelChoices and
 * SpeechModelPanel in feature/thread/Voice.kt, https://github.com/213YaZ786/SMS); the voice keyboard options are
 * Transcribro's, named as in its SettingsStartScreen
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/settings/SettingsStartScreen.kt
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onGuide: () -> Unit, onModels: () -> Unit) {
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var updatesOpen by rememberSaveable { mutableStateOf(false) }

    val pageScroll = rememberScrollState()
    FloatingFrame(
        bottom = 0.dp,
        scroll = pageScroll,
        top = { FloatingTop("Settings", leading = { FloatingAction(AppIcons.ArrowBack, "Back", onBack) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(horizontal = LocalReadableInset.current)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))

            Section(stringResource(R.string.models_title)) {
                SettingRow(title = stringResource(R.string.models_title), summary = stringResource(R.string.models_row_summary), onClick = onModels)
            }

            Section("Network") {
                SwitchRow(
                    title = "Network",
                    summary = stringResource(R.string.network_summary),
                    checked = settings.networkAllowed,
                    onChange = { on -> store.update { it.copy(networkAllowed = on) } }
                )
            }

            Section("Voice keyboard") {
                SwitchRow(
                    title = "Auto-start recognition",
                    summary = "Start listening when the keyboard comes up",
                    checked = settings.imeAutoStart,
                    onChange = { on -> store.update { it.copy(imeAutoStart = on) } }
                )
                SwitchRow(
                    title = "Auto-stop recognition",
                    summary = "Stop listening when speech stops",
                    checked = settings.imeAutoStop,
                    onChange = { on -> store.update { it.copy(imeAutoStop = on) } }
                )
                SwitchRow(
                    title = "Auto-send transcription",
                    summary = "Send the words when speech stops",
                    checked = settings.imeAutoSend,
                    onChange = { on -> store.update { it.copy(imeAutoSend = on) } }
                )
                SwitchRow(
                    title = "Auto-switch to the previous keyboard",
                    summary = "Go back to it when listening ends",
                    checked = settings.imeSwitchBack,
                    onChange = { on -> store.update { it.copy(imeSwitchBack = on) } }
                )
            }

            Section("About") {
                // The same first row in every app, as Dialer's "Guide" tile (YAZ/Dialer feature/settings/SettingsScreen.kt) and Store's (onGuide).
                SettingRow(title = "Guide", summary = "See the welcome pages again", onClick = onGuide)
                SettingRow(title = "Updates", summary = updatesLabel(settings.updates), onClick = { updatesOpen = true })
                SettingRow(title = "Language ${BuildConfig.VERSION_NAME}", summary = "GPL-3.0 License", onClick = null)
                SettingRow(
                    title = "Source code",
                    summary = "github.com/213YaZ786/Language",
                    onClick = { uriHandler.openUri("https://github.com/213YaZ786/Language") }
                )
                SettingRow(
                    title = "Transcribro",
                    summary = "soupslurpr, ISC License",
                    onClick = { uriHandler.openUri("https://github.com/soupslurpr/Transcribro") }
                )
                SettingRow(
                    title = "Offline Translator",
                    summary = "David Ventura, GPL-3.0 License",
                    onClick = { uriHandler.openUri("https://github.com/DavidVentura/offline-translator") }
                )
                SettingRow(
                    title = "Speech engine",
                    summary = "whisper.cpp, MIT License",
                    onClick = { uriHandler.openUri("https://github.com/ggml-org/whisper.cpp") }
                )
                SettingRow(
                    title = "Speech models",
                    summary = "OpenAI Whisper, MIT License",
                    onClick = { uriHandler.openUri("https://github.com/openai/whisper") }
                )
                SettingRow(
                    title = "Voice detector",
                    summary = "Silero VAD, MIT License",
                    onClick = { uriHandler.openUri("https://github.com/snakers4/silero-vad") }
                )
                SettingRow(
                    title = "Android",
                    summary = "Android Open Source Project, Apache License 2.0",
                    onClick = { uriHandler.openUri("https://source.android.com") }
                )
                SettingRow(
                    title = "Kõnele speechutils",
                    summary = "Apache License 2.0",
                    onClick = { uriHandler.openUri("https://github.com/Kaljurand/speechutils") }
                )
                SettingRow(
                    title = "Icons",
                    summary = "Material Icons by Google, Apache License 2.0",
                    onClick = { uriHandler.openUri("https://github.com/google/material-design-icons") }
                )
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (updatesOpen) ChoiceDialog(
        "Updates",
        UpdateMode.entries.map { it to updatesLabel(it) },
        settings.updates,
        { mode ->
            store.update { it.copy(updates = mode) }
            if (mode == UpdateMode.INSTALL && !Updates.canInstall(context)) Updates.allowInstalls(context)
        }
    ) { updatesOpen = false }
}

/** A titled group of rows on one rounded zone. Copied from Calculator's SettingsScreen (Section). */
@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
    ZoneSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(content = content)
    }
}

/** A row of a section. Copied from Calculator's SettingsScreen (SettingRow). */
@Composable
internal fun SettingRow(title: String, summary: String?, onClick: (() -> Unit)?, quiet: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val haptics = rememberHaptics()
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = summary?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable { if (!quiet) haptics.tick(); onClick() } else Modifier
    )
}

/** A row with a glass switch, the whole row its target. Copied from Calculator's SettingsScreen (SwitchRow). */
@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val change = { on: Boolean -> haptics.toggle(on); onChange(on) }
    SettingRow(title, summary, onClick = { change(!checked) }, quiet = true, trailing = { GlassSwitch(checked = checked, onCheckedChange = change) })
}

/** A dialog of radio choices. Copied from Calculator's SettingsScreen (ChoiceDialog). */
@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(value); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassRadio(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The update modes in words. Copied from Calculator's SettingsScreen (updatesLabel). */
private fun updatesLabel(mode: UpdateMode): String = when (mode) {
    UpdateMode.OFF -> "Off"
    UpdateMode.NOTIFY -> "Notify me"
    UpdateMode.INSTALL -> "Install automatically"
}
