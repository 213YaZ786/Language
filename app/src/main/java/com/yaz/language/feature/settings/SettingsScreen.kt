package com.yaz.language.feature.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yaz.language.BuildConfig
import com.yaz.language.R
import com.yaz.language.core.update.UpdateMode
import com.yaz.language.core.update.Updates
import com.yaz.language.data.settings.Settings
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.navigation.LocalReadableInset
import com.yaz.language.navigation.WidthClass
import com.yaz.language.ui.component.ChoiceDialog
import com.yaz.language.ui.component.FloatingAction
import com.yaz.language.ui.component.FloatingFrame
import com.yaz.language.ui.component.FloatingTop
import com.yaz.language.ui.component.LargeTitle
import com.yaz.language.ui.component.SettingsActionTile
import com.yaz.language.ui.component.SettingsFoldedSections
import com.yaz.language.ui.component.SettingsHeaderSpacer
import com.yaz.language.ui.component.SettingsListDetail
import com.yaz.language.ui.component.SettingsSection
import com.yaz.language.ui.component.SettingsSectionTitle
import com.yaz.language.ui.component.SettingsSwitchTile
import com.yaz.language.ui.component.SettingsTile
import com.yaz.language.ui.component.SettingsTileDialogs
import com.yaz.language.ui.component.SettingsTileGrid
import com.yaz.language.ui.icon.AppIcons
import org.koin.compose.koinInject

/**
 * Settings as tiles, laid out as SMS's SettingsScreen (our own app, the model of every YAZ app: SettingsSection,
 * SettingsSwitchTile and SettingsActionTile in Modules' SettingsTiles, the sections folded one under another on a
 * phone and a list with the chosen section's page on a wide window, a long press explaining a tile)
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/settings/SettingsScreen.kt
 * The tiles are Android Quick Settings' (AOSP SystemUI QSTileViewImpl). The settings are the ones this screen has always
 * had: Models, the Network switch, the voice keyboard options (Transcribro's, named as in its SettingsStartScreen
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/settings/SettingsStartScreen.kt),
 * updates, the guide, the version and the credits.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onGuide: () -> Unit, onModels: () -> Unit) {
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    SettingsContent(
        settings = settings,
        version = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onGuide = onGuide,
        onModels = onModels,
        onChange = { change -> store.update(change) },
        onUpdates = { mode ->
            store.update { it.copy(updates = mode) }
            // Installing needs Android's leave, asked when chosen.
            if (mode == UpdateMode.INSTALL && !Updates.canInstall(context)) Updates.allowInstalls(context)
        },
        onOpen = { uriHandler.openUri(it) }
    )
}

/** The groups of settings (SMS's SECTIONS): ids are what the screen keeps across a turn; the names come from strings.xml. */
private val DOWNLOADS = SettingsSection("downloads", "", AppIcons.Download)
private val KEYBOARD = SettingsSection("keyboard", "", AppIcons.Keyboard)
private val ABOUT = SettingsSection("about", "", AppIcons.Info)

/**
 * The screen with its data given, so a test draws it with fixed settings. Its own state is the open dialog, the tile
 * explained and the section open (SMS's SettingsScreen keeps the same).
 */
@Composable
internal fun SettingsContent(
    settings: Settings,
    version: String,
    onBack: () -> Unit,
    onGuide: () -> Unit,
    onModels: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onUpdates: (UpdateMode) -> Unit,
    onOpen: (String) -> Unit
) {
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    // The tile whose long press is open (its id), "" when none.
    var tileId by rememberSaveable { mutableStateOf("") }
    var sectionId by rememberSaveable { mutableStateOf(DOWNLOADS.id) }
    // On a phone every section starts folded; the one open stays open across a turn of the screen.
    var openId by rememberSaveable { mutableStateOf("") }
    // The page's scroll, held here so a drag on the header drives it (FloatingFrame's `scroll`).
    val compactScroll = rememberScrollState()
    val detailScroll = rememberSaveable(sectionId, saver = ScrollState.Saver) { ScrollState(0) }

    val sections = listOf(
        SettingsSection(DOWNLOADS.id, stringResource(R.string.section_downloads), DOWNLOADS.icon),
        SettingsSection(KEYBOARD.id, stringResource(R.string.section_voice_keyboard), KEYBOARD.icon),
        SettingsSection(ABOUT.id, stringResource(R.string.section_about), ABOUT.icon)
    )
    val named = sections.first { it.id == sectionId }

    val tiles: Map<String, List<SettingsSwitchTile>> = mapOf(
        DOWNLOADS.id to listOf(
            SettingsSwitchTile(
                id = "network", name = stringResource(R.string.network), title = stringResource(R.string.network), icon = AppIcons.Language,
                summary = stringResource(R.string.network_summary),
                checked = settings.networkAllowed, onChange = { on -> onChange { it.copy(networkAllowed = on) } }
            )
        ),
        KEYBOARD.id to listOf(
            SettingsSwitchTile(
                id = "autoStart", name = stringResource(R.string.ime_auto_start_name), title = stringResource(R.string.ime_auto_start_title),
                icon = AppIcons.Play, summary = stringResource(R.string.ime_auto_start_summary),
                checked = settings.imeAutoStart, onChange = { on -> onChange { it.copy(imeAutoStart = on) } }
            ),
            SettingsSwitchTile(
                id = "autoStop", name = stringResource(R.string.ime_auto_stop_name), title = stringResource(R.string.ime_auto_stop_title),
                icon = AppIcons.Stop, summary = stringResource(R.string.ime_auto_stop_summary),
                checked = settings.imeAutoStop, onChange = { on -> onChange { it.copy(imeAutoStop = on) } }
            ),
            SettingsSwitchTile(
                id = "autoSend", name = stringResource(R.string.ime_auto_send_name), title = stringResource(R.string.ime_auto_send_title),
                icon = AppIcons.Send, summary = stringResource(R.string.ime_auto_send_summary),
                checked = settings.imeAutoSend, onChange = { on -> onChange { it.copy(imeAutoSend = on) } }
            ),
            SettingsSwitchTile(
                id = "switchBack", name = stringResource(R.string.ime_switch_back_name), title = stringResource(R.string.ime_switch_back_title),
                icon = AppIcons.Keyboard, summary = stringResource(R.string.ime_switch_back_summary),
                checked = settings.imeSwitchBack, onChange = { on -> onChange { it.copy(imeSwitchBack = on) } }
            )
        )
    )

    val updatesText = updatesLabel(settings.updates)
    val licence = stringResource(R.string.about_license)
    // A credit: the tile names the work, its second line says whose and under which licence, a tap opens its page.
    fun credit(id: String, name: String, value: String, summary: String, icon: ImageVector, url: String) =
        SettingsActionTile(id = id, name = name, title = name, icon = icon, value = value, summary = summary, onClick = { onOpen(url) })

    val actions: Map<String, List<SettingsActionTile>> = mapOf(
        DOWNLOADS.id to listOf(
            SettingsActionTile(
                id = "models", name = stringResource(R.string.models_title), title = stringResource(R.string.models_title), icon = AppIcons.Download,
                value = stringResource(R.string.models_row_summary), summary = stringResource(R.string.models_row_summary),
                onClick = onModels
            )
        ),
        ABOUT.id to listOf(
            // The same first tile in every app, as Dialer's "Guide" tile (YAZ/Dialer feature/settings/SettingsScreen.kt) and SMS's.
            SettingsActionTile(
                id = "guide", name = stringResource(R.string.guide), title = stringResource(R.string.guide), icon = AppIcons.Play,
                value = stringResource(R.string.guide_value), summary = stringResource(R.string.guide_summary), onClick = onGuide
            ),
            SettingsActionTile(
                id = "updates", name = stringResource(R.string.updates), title = stringResource(R.string.updates), icon = AppIcons.Update,
                value = updatesText, summary = updatesText, onClick = { updatesOpen = true }
            ),
            SettingsActionTile(
                id = "version", name = stringResource(R.string.app_name), title = stringResource(R.string.about_title, version), icon = AppIcons.Info,
                value = version, summary = licence
            ),
            credit("source", stringResource(R.string.source_code), "github.com/213YaZ786/Language", "github.com/213YaZ786/Language", AppIcons.Code, "https://github.com/213YaZ786/Language"),
            credit("transcribro", stringResource(R.string.credit_transcribro), stringResource(R.string.credit_transcribro_value), stringResource(R.string.credit_transcribro_summary), AppIcons.Keyboard, "https://github.com/soupslurpr/Transcribro"),
            credit("translator", stringResource(R.string.credit_translator), stringResource(R.string.credit_translator_value), stringResource(R.string.credit_translator_summary), AppIcons.Translate, "https://github.com/DavidVentura/offline-translator"),
            credit("engine", stringResource(R.string.credit_engine), stringResource(R.string.credit_engine_value), stringResource(R.string.credit_engine_summary), AppIcons.Memory, "https://github.com/ggml-org/whisper.cpp"),
            credit("whisperModels", stringResource(R.string.credit_models), stringResource(R.string.credit_models_value), stringResource(R.string.credit_models_summary), AppIcons.GraphicEq, "https://github.com/openai/whisper"),
            credit("vad", stringResource(R.string.credit_vad), stringResource(R.string.credit_vad_value), stringResource(R.string.credit_vad_summary), AppIcons.Mic, "https://github.com/snakers4/silero-vad"),
            credit("android", stringResource(R.string.credit_android), stringResource(R.string.credit_android_value), stringResource(R.string.credit_android_summary), AppIcons.Settings, "https://source.android.com"),
            credit("konele", stringResource(R.string.credit_konele), stringResource(R.string.credit_konele_value), stringResource(R.string.credit_konele_summary), AppIcons.Mic, "https://github.com/Kaljurand/speechutils"),
            credit("icons", stringResource(R.string.credit_icons), stringResource(R.string.credit_icons_value), stringResource(R.string.credit_icons_summary), AppIcons.Copy, "https://github.com/google/material-design-icons")
        )
    )

    fun tilesOf(which: SettingsSection): List<SettingsTile> = tiles[which.id].orEmpty() + actions[which.id].orEmpty()

    // One section's page: its name, then its tiles: the switches first, then what opens something.
    val page: @Composable ColumnScope.(SettingsSection) -> Unit = { which ->
        SettingsSectionTitle(which.title)
        val list = tilesOf(which)
        if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
    }

    val title = stringResource(R.string.settings)
    val back = stringResource(R.string.back)
    // The window decides: a list and a page side by side from Medium up, as a phone held upright keeps one page.
    // The panes take the whole width, so the readable inset is dropped.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = WidthClass.of(maxWidth) != WidthClass.COMPACT
        CompositionLocalProvider(LocalReadableInset provides if (wide) 0.dp else LocalReadableInset.current) {
            FloatingFrame(
                bottom = 0.dp,
                scroll = if (wide) detailScroll else compactScroll,
                top = { FloatingTop(title, leading = { FloatingAction(AppIcons.ArrowBack, back, onBack) }) },
                largeTitle = LargeTitle(title)
            ) { padding ->
                if (wide) {
                    SettingsListDetail(
                        padding = padding,
                        sections = sections,
                        section = named,
                        onSection = { sectionId = it.id },
                        detailScroll = detailScroll,
                        detail = { page(named) }
                    )
                } else {
                    // Scrolls at full width, rows pushed in by the readable inset, so the margins of a tablet scroll
                    // like the rest (SMS's SettingsScreen).
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(compactScroll)
                            .padding(horizontal = LocalReadableInset.current)
                    ) {
                        SettingsHeaderSpacer(padding)
                        Spacer(Modifier.height(24.dp))
                        SettingsFoldedSections(sections, open = sections.firstOrNull { it.id == openId }, onOpen = { openId = it?.id.orEmpty() }) { which ->
                            val list = tilesOf(which)
                            if (list.isNotEmpty()) SettingsTileGrid(list) { tileId = it.id }
                        }
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }

    (tiles.values.flatten() + actions.values.flatten()).firstOrNull { it.id == tileId }?.let { tile ->
        SettingsTileDialogs(tile, onDismiss = { tileId = "" })
    }

    if (updatesOpen) {
        ChoiceDialog(
            title = stringResource(R.string.updates),
            options = UpdateMode.entries.map { it to updatesLabel(it) },
            selected = settings.updates,
            onPick = onUpdates,
            onDismiss = { updatesOpen = false }
        )
    }
}

/** The update modes in words (SMS's updatesLabel). */
@Composable
private fun updatesLabel(mode: UpdateMode): String = stringResource(
    when (mode) {
        UpdateMode.OFF -> R.string.updates_off
        UpdateMode.NOTIFY -> R.string.updates_notify
        UpdateMode.INSTALL -> R.string.updates_install
    }
)
