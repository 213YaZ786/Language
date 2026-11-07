/*
 * The welcome guide as tiles, after FlorisBoard's SetupScreen: every step is read from the system (what is enabled,
 * selected, allowed) and shown done or not, the tiles are Modules' SetupTiles.
 *
 * Copyright (C) 2021-2025 The FlorisBoard Contributors, Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0), translated from
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt
 */
package com.yaz.language.feature.welcome

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.language.core.VoiceSpeech
import com.yaz.language.core.update.StoreLink
import com.yaz.language.core.update.Updates
import com.yaz.language.core.voice.SpeechModel
import com.yaz.language.feature.common.isPhoneVoiceInput
import com.yaz.language.feature.common.openVoiceInputSettings
import com.yaz.language.feature.common.rememberMicrophone
import com.yaz.language.translate.LanguageStateManager
import com.yaz.language.translate.localizedName
import com.yaz.language.ui.component.BoldButton
import com.yaz.language.ui.component.LoadingMark
import com.yaz.language.ui.component.SetupTile
import com.yaz.language.ui.component.SetupTileDialog
import com.yaz.language.ui.component.SetupTileGrid
import com.yaz.language.ui.component.leftText
import com.yaz.language.ui.component.requiredDone
import com.yaz.language.ui.component.requiredLeft
import com.yaz.language.ui.icon.AppIcons
import org.koin.compose.koinInject

/**
 * The few choices worth making, the best one already set, as tiles: each is read from the system again each time the
 * screen comes back (SetupScreen reads isFlorisBoardEnabled, isFlorisBoardSelected and hasNotificationPermission,
 * and sets the current step from them), and turns done by itself. [onLanguages] opens the Models screen.
 * Ported from WelcomeScreen of SMS (the model for this phone chosen, fetched on a tap, its progress shown; our own
 * app, from which this screen was copied)
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/main/WelcomeScreen.kt#WelcomeScreen
 * Steps, only the choices that matter (the user, 2026-11-06; the rest takes the best preset, never asked): the
 * microphone, the engine, Language as the phone's voice input (these three required), the languages, and updates
 * only when the app does not update through Store (SetupScreen shows a step only when it applies, as its
 * notification step on API 33+). The voice keyboard stays on the Models screen, off by default.
 */
@Composable
fun rememberSetupTiles(onLanguages: () -> Unit): List<SetupTile> {
    val context = LocalContext.current
    val speech: VoiceSpeech = koinInject()
    val states: LanguageStateManager = koinInject()
    val model = speech.model
    val mic = rememberMicrophone()
    val state by model.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val system by model.system.collectAsState()
    val available by states.languageState.collectAsState()
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        model.recheck()
        onPauseOrDispose { }
    }
    val metered = remember { model.metered() }
    val isVoiceInput = remember(checks) { isPhoneVoiceInput(context) }
    // When Store installs the app's updates, the guide asks nothing about them.
    val viaStore = remember(checks) { StoreLink.viaStore(context) }
    val canInstall = remember(checks) { Updates.canInstall(context) }
    val hasNotificationPermission = remember(checks) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }

    // The phone's recognizer when it has the language, else the model for this phone, already chosen.
    val engine = if (model.usesSystem) "This phone's recognizer" else "Whisper ${model.pin.label}, ${model.pin.bytes / 1_000_000} MB"
    // Every language with a model in, English apart (it needs none).
    val installed = available.availableLanguages
        .filter { !it.language.isEnglish && it.availability.translatorFiles }
        .map { it.language.localizedName() }

    return listOfNotNull(
        SetupTile(
            id = "microphone", name = "Microphone", title = "Microphone", icon = AppIcons.Mic,
            state = when {
                mic.granted -> "Allowed"
                mic.refusedForGood -> "Refused, open settings"
                else -> "Not allowed"
            },
            summary = "Language listens only while you dictate or translate by voice, and Android shows the microphone light then. Tap to allow it" +
                if (mic.refusedForGood) "; it was refused, so Android's settings page for Language opens." else ".",
            done = mic.granted, required = true, error = mic.refusedForGood,
            onClick = mic.request
        ),
        when (val s = state) {
            is SpeechModel.State.Fetching -> SetupTile(
                id = "engine", name = "Speech engine", title = "Speech engine", icon = AppIcons.Memory,
                state = "${(s.done * 100).toInt()} %",
                summary = "$engine is being fetched, once. A tap stops it.",
                done = false, required = true, progress = s.done,
                onClick = { model.cancel() }
            )
            SpeechModel.State.Failed -> SetupTile(
                id = "engine", name = "Speech engine", title = "Speech engine", icon = AppIcons.Memory,
                state = "Could not get it, tap to retry",
                summary = "$engine could not be fetched. Check the connection, then tap to try again.",
                done = false, required = true, error = true,
                onClick = { model.fetch() }
            )
            SpeechModel.State.Missing, SpeechModel.State.Ready -> SetupTile(
                id = "engine", name = "Speech engine", title = "Speech engine", icon = AppIcons.Memory,
                state = if (s == SpeechModel.State.Missing && metered) "$engine\nOn mobile data" else engine,
                summary = "$engine turns your voice into text on the phone, with no connection once it is here. " +
                    if (s == SpeechModel.State.Ready) "It is ready." else "Tap to fetch it, once.",
                done = s == SpeechModel.State.Ready, required = true,
                onClick = { if (s == SpeechModel.State.Missing) model.fetch() }
            )
        },
        SetupTile(
            id = "voiceInput", name = "Voice input", title = "Voice input", icon = AppIcons.GraphicEq,
            state = if (isVoiceInput) "Language" else "Not chosen",
            summary = "Apps that ask Android for voice input are heard by Language. Tap to choose Language in Android's settings.",
            done = isVoiceInput, required = true,
            onClick = { openVoiceInputSettings(context) }
        ),
        SetupTile(
            id = "languages", name = "Languages", title = "Languages", icon = AppIcons.Translate,
            state = installed.joinToString(", ").ifEmpty { "None yet" },
            summary = "The languages Language translates, each fetched once and kept on the phone. " +
                (if (installed.isEmpty()) "None yet. " else "In: ${installed.joinToString(", ")}. ") + "Tap to add more.",
            done = installed.isNotEmpty(), required = false,
            onClick = onLanguages
        ),
        if (!viaStore) SetupTile(
            id = "updates", name = "Updates", title = "Updates", icon = AppIcons.Update,
            state = when {
                !hasNotificationPermission -> "Notifications off"
                !canInstall -> "Installs not allowed"
                else -> "Allowed"
            },
            summary = "Language can tell you of a new version and install it for you. It needs notifications and Android's leave to install. Tap to allow the one missing.",
            done = hasNotificationPermission && canInstall, required = false,
            onClick = {
                if (!hasNotificationPermission) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                else if (!canInstall) Updates.allowInstalls(context)
            }
        ) else null
    )
}

/**
 * The guide: the logo, the title, how many steps are left, the tiles, then Start (Done when reopened from Settings),
 * which waits for the required ones. Laid out as SetupScreen: header, steps, footer with the one button.
 * Ported from SetupScreen#SetupScreen of
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt
 */
@Composable
fun WelcomeScreen(reopened: Boolean, onLanguages: () -> Unit, onStart: () -> Unit) {
    val steps = rememberSetupTiles(onLanguages)
    // The tile whose long press is open (its id), "" when none.
    var explained by rememberSaveable { mutableStateOf("") }

    WelcomeContent(steps, reopened, onStart) { explained = it.id }

    steps.firstOrNull { it.id == explained }?.let { tile ->
        SetupTileDialog(tile, onDismiss = { explained = "" })
    }
}

/**
 * What the guide draws from the tiles it is given: the layout of SetupScreen (header, steps, footer with the one
 * button), split from [WelcomeScreen] as HomeScreen is from HomeContent, so that a picture can be taken with fixed
 * tiles, without the system checks. Ported from
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt#SetupScreen
 * and split as
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/home/HomeScreen.kt#HomeContent
 */
@Composable
internal fun WelcomeContent(tiles: List<SetupTile>, reopened: Boolean, onStart: () -> Unit, onExplain: (SetupTile) -> Unit = {}) {
    val steps = tiles
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(40.dp))
            // The mark turning, as the guides before the tiles drew it (SMS's WelcomeScreen, LoadingMark(size = 160.dp));
            // Language's own size of then, 140 dp (the user, 2026-11-07).
            LoadingMark(size = 140.dp)
            Spacer(Modifier.height(20.dp))
            // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
            Text("Welcome to Language", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(steps.leftText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            SetupTileGrid(steps, onExplain)

            Spacer(Modifier.height(32.dp))
            val ready = steps.requiredDone()
            BoldButton(filled = true, enabled = ready, onClick = onStart) { Text(if (reopened) "Done" else "Start") }
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Still needed: " + steps.requiredLeft().joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
