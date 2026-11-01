package com.yaz.language.feature.home

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yaz.language.BuildConfig
import com.yaz.language.R
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.feature.common.rememberMicrophone
import com.yaz.language.navigation.LocalReadableInset
import com.yaz.language.translate.DownloadStatus
import com.yaz.language.translate.Language
import com.yaz.language.translate.inFlightProgress
import com.yaz.language.translate.aidl.TranslateGuards
import com.yaz.language.translate.localizedName
import com.yaz.language.ui.component.BoldButton
import com.yaz.language.ui.component.ChoiceDialog
import com.yaz.language.ui.component.FloatingAction
import com.yaz.language.ui.component.FloatingFrame
import com.yaz.language.ui.component.FloatingTop
import com.yaz.language.ui.component.LoadingMark
import com.yaz.language.ui.component.QuietButton
import com.yaz.language.ui.component.UpdatePrompt
import com.yaz.language.ui.component.ZoneSurface
import com.yaz.language.ui.component.rememberHaptics
import com.yaz.language.ui.icon.AppIcons
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Home is the translate screen: Google Translate's layout (the language bar with the swap, the source card, then the
 * result card with Copy, Listen and Share), with offline-translator's pieces behind it (v0.8.7, commit
 * e7716c6859e3d90b8883c667c1a3cfeef407df08, GPL-3.0, ui/components): LanguageSelectionRow (source, swap, target),
 * LanguageSelector (a language picked from a list, here Modules' ChoiceDialog in place of the Material dropdown),
 * InputSection (the field, Paste and Clear), ActionButtons (paste), DetectedLanguageSection (the detected language
 * offered as the source) and LanguageDownloadButton (Get with progress and Cancel, Retry on failure)
 * https://github.com/DavidVentura/offline-translator/tree/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/ui/components
 * The mic is the dictation of HomeViewModel filling the field. Built on Calculator's glass screens (FloatingFrame
 * under a title, ZoneSurface cards) https://github.com/213YaZ786/Calculator
 * The screen's state is [TranslateViewModel]; the status rows (microphone, voice input, voice keyboard) are on the
 * Models screen.
 */
@Composable
fun HomeScreen(
    onSettings: () -> Unit,
    onModels: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
    translate: TranslateViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    val haptics = rememberHaptics()
    val ui by translate.ui.collectAsState()
    val input by translate.input.collectAsState()
    val heard by viewModel.heard.collectAsState()
    val mic = rememberMicrophone()

    // Leaving the screen ends listening and drops what is held.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stop(false) }
    DisposableEffect(Unit) { onDispose { viewModel.stop(false) } }

    // Listen: the phone's engine, bound only while the screen is in front.
    val speaker = remember { Speaker(context) }
    val speaking by speaker.speaking.collectAsState()
    val noVoice by speaker.problem.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { speaker.shutdown() }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    LaunchedEffect(ui.output) { speaker.stop() }

    // A feel for listening starting and ending, and for a failure.
    val listening = heard.phase == Phase.LISTENING
    var wasListening by remember { mutableStateOf(listening) }
    LaunchedEffect(listening) {
        if (listening != wasListening) {
            if (listening) haptics.firm() else haptics.done()
            wasListening = listening
        }
    }
    LaunchedEffect(heard.note) { if (heard.note != null) haptics.reject() }

    // Everything that reaches the field (typed, pasted, dictated) goes through the cap, and the screen says when it cut.
    var cut by rememberSaveable { mutableStateOf(false) }
    val enter: (String) -> Unit = { raw ->
        val capped = capInput(raw)
        cut = capped.cut
        translate.setText(capped.text)
    }

    // The dictation fills the field: the words come after the text that was there when the mic was tapped.
    var base by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(heard.phase, heard.committed, heard.partial) {
        val before = base ?: return@LaunchedEffect
        if (heard.text.isNotBlank()) enter(joinWords(before, heard.text))
        if (heard.phase == Phase.IDLE) {
            base = null
            viewModel.takeWords()
        }
    }
    val onMic = {
        when {
            heard.phase == Phase.LISTENING -> viewModel.stop(true)
            heard.phase != Phase.IDLE -> Unit
            !mic.granted -> mic.request()
            else -> {
                base = input
                viewModel.start(noEngine = onModels)
            }
        }
    }

    var picker by rememberSaveable { mutableIntStateOf(PICK_NONE) }
    HomeContent(
        ui = ui,
        input = input,
        heard = heard,
        clipped = cut,
        hasClipboardText = rememberClipboardHasText(),
        speaking = speaking,
        noVoice = noVoice,
        onModels = onModels,
        onSettings = onSettings,
        onSource = { haptics.tick(); picker = PICK_SOURCE },
        onTarget = { haptics.tick(); picker = PICK_TARGET },
        onSwap = { if (translate.swap()) haptics.done() else haptics.reject() },
        onInput = enter,
        onPaste = { haptics.tick(); pastedText(context)?.let(enter) },
        onMic = { haptics.tick(); onMic() },
        onClear = { haptics.tick(); translate.clear() },
        onDetected = { haptics.tick(); translate.pickSource(ui.detected) },
        onCopy = { ui.output?.let { copyText(context, it); haptics.done() } },
        onListen = {
            haptics.tick()
            val text = ui.output
            val language = ui.target
            if (speaking) speaker.stop() else if (text != null && language != null) speaker.speak(text, language.locale())
        },
        onShare = {
            haptics.tick()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ui.output.orEmpty())
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onGet = { haptics.done(); translate.get(ui.toGet) },
        onCancel = { haptics.tick(); translate.cancel(ui.toGet) }
    )

    // The language lists, offline-translator's LanguageSelector dropdown as Modules' ChoiceDialog: the Detect entry first
    // for the source, then the languages on the phone, then the others.
    val resources = LocalContext.current.resources
    val labels = remember(ui.languages, ui.onPhone) {
        ui.languages.associate { language ->
            val name = language.localizedName()
            language.code to if (language.code in ui.onPhone) resources.getString(R.string.on_the_phone_name, name) else name
        }
    }
    if (picker == PICK_SOURCE) {
        val detect = stringResource(R.string.detect)
        ChoiceDialog(
            title = stringResource(R.string.pick_source),
            options = listOf("" to detect) + ui.languages.map { it.code to labels.getValue(it.code) },
            selected = ui.source?.code ?: "",
            onPick = { code -> translate.pickSource(ui.languages.firstOrNull { it.code == code }) },
            onDismiss = { picker = PICK_NONE }
        )
    }
    if (picker == PICK_TARGET) {
        ChoiceDialog(
            title = stringResource(R.string.pick_target),
            options = ui.languages.map { it.code to labels.getValue(it.code) },
            selected = ui.target?.code,
            onPick = { code -> ui.languages.firstOrNull { it.code == code }?.let(translate::pickTarget) },
            onDismiss = { picker = PICK_NONE }
        )
    }

    UpdatePrompt(settings.updates, BuildConfig.VERSION_NAME)
}

private const val PICK_NONE = 0
private const val PICK_SOURCE = 1
private const val PICK_TARGET = 2

/**
 * The text that goes into the field, and whether it was cut. A plain holder, as TranslateGuards.Clip (this app's
 * translate/aidl/TranslateGuards.kt) is for the panel's text.
 */
internal class Capped(val text: String, val cut: Boolean)

/**
 * The text of the field cut at TranslateGuards.MAX_CHARS, through this app's own [TranslateGuards.withinCap] and
 * [TranslateGuards.clip] (translate/aidl/TranslateGuards.kt, the cap the panel's ProcessTextActivity uses for the text it
 * is given, after offline-translator's ProcessTextActivity.kt
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/ProcessTextActivity.kt),
 * so a surrogate pair is never split. A blank text longer than the cap is nothing worth keeping and reads as empty.
 */
internal fun capInput(text: String): Capped =
    if (TranslateGuards.withinCap(text)) Capped(text, false)
    else Capped(TranslateGuards.clip(text)?.text.orEmpty(), true)

/**
 * The screen with its data given: the title bar, the language bar, the source card and the result card. The state and the
 * effects stay in [HomeScreen], so a test draws this with fixed data: state hoisting, as Now in Android splits its
 * ForYouRoute (state, effects) from ForYouScreen (what is drawn)
 * https://github.com/android/nowinandroid/blob/main/feature/foryou/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/ForYouScreen.kt
 */
@Composable
internal fun HomeContent(
    ui: TranslateUi,
    input: String,
    heard: Heard,
    clipped: Boolean,
    hasClipboardText: Boolean,
    speaking: Boolean,
    noVoice: Boolean,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onSource: () -> Unit,
    onTarget: () -> Unit,
    onSwap: () -> Unit,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onDetected: () -> Unit,
    onCopy: () -> Unit,
    onListen: () -> Unit,
    onShare: () -> Unit,
    onGet: () -> Unit,
    onCancel: () -> Unit
) {
    val pageScroll = rememberScrollState()
    FloatingFrame(
        bottom = 0.dp,
        scroll = pageScroll,
        top = {
            FloatingTop(
                stringResource(R.string.app_name),
                leading = { FloatingAction(AppIcons.Download, stringResource(R.string.models), onModels) },
                trailing = { FloatingAction(AppIcons.Settings, stringResource(R.string.settings), onSettings) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(horizontal = LocalReadableInset.current + 16.dp)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            Spacer(Modifier.height(8.dp))

            LanguageBar(ui = ui, onSource = onSource, onTarget = onTarget, onSwap = onSwap)

            Spacer(Modifier.height(16.dp))
            InputCard(
                ui = ui,
                input = input,
                heard = heard,
                clipped = clipped,
                hasClipboardText = hasClipboardText,
                onInput = onInput,
                onPaste = onPaste,
                onMic = onMic,
                onClear = onClear,
                onDetected = onDetected
            )

            if (input.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                ResultCard(
                    ui = ui,
                    speaking = speaking,
                    noVoice = noVoice,
                    onCopy = onCopy,
                    onListen = onListen,
                    onShare = onShare,
                    onGet = onGet,
                    onCancel = onCancel,
                    onSettings = onSettings
                )
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/**
 * The language bar: the source, the swap, the target, as offline-translator's LanguageSelectionRow lays them out and
 * as Google Translate's bar does, each name on a pill of glass that opens its list. The source reads Detect until
 * the text is recognised, then the language it found.
 */
@Composable
private fun LanguageBar(ui: TranslateUi, onSource: () -> Unit, onTarget: () -> Unit, onSwap: () -> Unit) {
    val source = ui.source
    val detected = ui.detected
    val sourceName = when {
        source != null -> source.localizedName()
        detected != null -> stringResource(R.string.detected_name, detected.localizedName())
        else -> stringResource(R.string.detect)
    }
    val canSwap = ui.from != null && ui.target != null && ui.from != ui.target
    // The arrows turn half a turn at each swap.
    var turns by remember { mutableIntStateOf(0) }
    val angle by animateFloatAsState(turns * 180f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "swap")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LanguagePill(sourceName, onSource, Modifier.weight(1f))
        ZoneSurface(
            shape = CircleShape,
            onClick = { if (canSwap) { turns++; onSwap() } },
            modifier = Modifier.size(48.dp).alpha(if (canSwap) 1f else 0.4f)
        ) {
            Icon(
                AppIcons.SwapHoriz,
                contentDescription = stringResource(R.string.swap_languages),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp).graphicsLayer { rotationZ = angle }
            )
        }
        LanguagePill(ui.target?.localizedName().orEmpty(), onTarget, Modifier.weight(1f))
    }
}

/** A language name on a pill of glass, centred; a long name wraps, the pill has a minimum height only. */
@Composable
private fun LanguagePill(name: String, onClick: () -> Unit, modifier: Modifier) {
    ZoneSurface(shape = CircleShape, onClick = onClick, modifier = modifier.heightIn(min = 52.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The source card: the field, then the detected language when it is not the picked one, then Paste, the mic and Clear
 * across the width (offline-translator's InputSection puts Clear or Paste in its card; the mic is the dictation).
 */
@Composable
private fun InputCard(
    ui: TranslateUi,
    input: String,
    heard: Heard,
    clipped: Boolean,
    hasClipboardText: Boolean,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onDetected: () -> Unit
) {
    val listening = heard.phase == Phase.LISTENING
    ZoneSurface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicTextField(
                value = input,
                onValueChange = onInput,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                decorationBox = { field ->
                    if (input.isEmpty()) {
                        Text(stringResource(R.string.input_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                }
            )
            heard.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            // The text was cut at the cap: said in the panel's words (process_clipped), under the field.
            if (clipped) {
                Text(
                    stringResource(R.string.input_clipped),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            val detected = ui.detected
            if (ui.source != null && detected != null && detected != ui.source && input.isNotBlank()) {
                ZoneSurface(shape = CircleShape, onClick = onDetected, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(
                        stringResource(R.string.detected_chip, detected.localizedName()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            val paste = stringResource(R.string.paste)
            val dictate = stringResource(if (listening) R.string.stop else R.string.dictate)
            val clear = stringResource(R.string.clear)
            BalancedButtons(
                listOf(
                    ButtonSpec(paste, ICON_ROOM) { CardButton(AppIcons.Paste, paste, onPaste, it, enabled = hasClipboardText) },
                    ButtonSpec(dictate, ICON_ROOM) { CardButton(if (listening) AppIcons.Stop else AppIcons.Mic, dictate, onMic, it) },
                    ButtonSpec(clear, ICON_ROOM) { CardButton(AppIcons.Close, clear, onClear, it, enabled = input.isNotEmpty()) }
                ),
                Modifier.widthIn(max = 520.dp).align(Alignment.CenterHorizontally)
            )
        }
    }
}

/**
 * The result card: the target language, then what the pair is doing. In turn: the language of the text unknown, packs
 * to get (offline-translator's LanguageDownloadButton: Get, progress with Cancel, Retry), a pair the packs cannot
 * make, the translation running, a failure, or the translation with Copy, Listen and Share (Google Translate's actions).
 */
@Composable
private fun ResultCard(
    ui: TranslateUi,
    speaking: Boolean,
    noVoice: Boolean,
    onCopy: () -> Unit,
    onListen: () -> Unit,
    onShare: () -> Unit,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit
) {
    val target = ui.target
    val from = ui.from
    val output = ui.output
    ZoneSurface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            target?.let {
                Text(it.localizedName(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            }
            when {
                from == null -> Note(stringResource(R.string.choose_source))
                ui.toGet.isNotEmpty() && target != null -> NeedBlock(ui, from, target, onGet, onCancel, onSettings)
                ui.unavailable && target != null -> Note(stringResource(R.string.unavailable_pair, from.localizedName(), target.localizedName()))
                output != null -> {
                    SelectionContainer(Modifier.fillMaxWidth()) {
                        Text(output, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                    }
                    val copy = stringResource(R.string.copy)
                    val listen = stringResource(if (speaking) R.string.stop else R.string.listen)
                    val share = stringResource(R.string.share)
                    BalancedButtons(
                        listOf(
                            ButtonSpec(copy, ICON_ROOM) { CardButton(AppIcons.Copy, copy, onCopy, it) },
                            ButtonSpec(listen, ICON_ROOM) { CardButton(if (speaking) AppIcons.Stop else AppIcons.VolumeUp, listen, onListen, it) },
                            ButtonSpec(share, ICON_ROOM) { CardButton(AppIcons.Share, share, onShare, it) }
                        ),
                        Modifier.widthIn(max = 520.dp)
                    )
                    if (noVoice && target != null) Note(stringResource(R.string.no_voice, target.localizedName()), error = true)
                }
                ui.error != null -> Note(stringResource(R.string.translate_failed), error = true)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingMark(size = 28.dp)
                    Text(stringResource(R.string.translating), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A line of the result card, centred. */
@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Which language to get, with its size, and the one action the state allows: Get, the progress with Cancel, Retry, or
 * Settings when the Network switch is off (it is honoured here too, DownloadManager refuses while it is off).
 */
@Composable
private fun NeedBlock(
    ui: TranslateUi,
    from: Language,
    to: Language,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val names = ui.toGet.map { it.language.localizedName() }
    val joined = if (names.size == 2) stringResource(R.string.and_join, names[0], names[1]) else names.joinToString()
    Note(stringResource(R.string.need_line, from.localizedName(), to.localizedName(), joined))
    val running = ui.toGet.mapNotNull { it.status.inFlightProgress }
    val failed = ui.toGet.any { it.status is DownloadStatus.Failed }
    when {
        running.isNotEmpty() -> {
            val progress = running.average().toFloat()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingMark(size = 28.dp, progress = progress)
                Text(stringResource(R.string.getting_percent, (progress * 100).toInt()), style = MaterialTheme.typography.bodyLarge)
            }
            QuietButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        !ui.networkAllowed -> {
            Note(stringResource(R.string.network_off_get, joined), error = true)
            BoldButton(onClick = onSettings) { Text(stringResource(R.string.settings), textAlign = TextAlign.Center) }
        }
        else -> {
            if (failed) Note(stringResource(R.string.could_not_get), error = true)
            val total = Formatter.formatShortFileSize(context, ui.toGet.sumOf { it.bytes })
            BoldButton(onClick = onGet) {
                Text(if (failed) stringResource(R.string.retry) else stringResource(R.string.get_size, total), textAlign = TextAlign.Center)
            }
        }
    }
}

/** The room an icon and its gap take inside a button of [BalancedButtons]. */
private val ICON_ROOM = 26.dp

/** A button of the cards: its icon, then its label at the group's size. */
@Composable
private fun CardButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    BoldButton(onClick = onClick, enabled = enabled, modifier = modifier.semantics { contentDescription = label; role = Role.Button }) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        GroupLabel(label)
    }
}
