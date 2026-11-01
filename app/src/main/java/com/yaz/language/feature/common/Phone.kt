package com.yaz.language.feature.common

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaz.language.core.VoiceSpeech
import com.yaz.language.data.settings.SettingsStore
import org.koin.compose.koinInject

/** The microphone right as a screen sees it, read again each time the screen comes back (after the Settings page). */
class MicrophoneState(
    val granted: Boolean,
    /** Refused with Android's "don't ask again": only the app's page in Settings can allow it now. */
    val refusedForGood: Boolean,
    /** Asks for the right, or opens the app's page in Settings when it was refused for good. */
    val request: () -> Unit
)

/**
 * RECORD_AUDIO as Android's runtime permission workflow says, and as Accompanist's permissions state tells a refusal
 * for good: asked before, not granted, and no rationale to show
 * https://developer.android.com/training/permissions/requesting
 * https://github.com/google/accompanist/blob/main/permissions/src/main/java/com/google/accompanist/permissions/MutablePermissionState.kt
 * ("asked before" is kept in the settings, since a refusal before the first question looks the same).
 */
@Composable
fun rememberMicrophone(): MicrophoneState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val store: SettingsStore = koinInject()
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    val granted = remember(checks) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val refusedForGood = !granted && store.current.micAsked && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    return MicrophoneState(granted, refusedForGood) {
        if (refusedForGood) {
            context.startActivity(VoiceSpeech.appSettingsIntent(context))
        } else {
            store.update { it.copy(micAsked = true) }
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/**
 * Whether Language is the phone's voice input: the service Android keeps in the secure setting "voice_recognition_service"
 * (a flattened component name) belongs to this app. The setting is AOSP's Settings.Secure.VOICE_RECOGNITION_SERVICE
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java
 * read by name with Settings.Secure.getString, the component with ComponentName.unflattenFromString.
 */
fun isPhoneVoiceInput(context: Context): Boolean = runCatching {
    val value = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
    ComponentName.unflattenFromString(value.orEmpty())?.packageName == context.packageName
}.getOrDefault(false)

/**
 * Whether Language's voice keyboard is turned on in the phone's keyboard list. Ported from Transcribro's StartScreen
 * isMyInputMethodEnabled
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/start/StartScreen.kt
 * (InputMethodManager.enabledInputMethodList holds an input method of this package).
 */
fun isVoiceKeyboardEnabled(context: Context): Boolean = runCatching {
    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        .enabledInputMethodList.any { it.packageName == context.packageName }
}.getOrDefault(false)

/**
 * Opens Android's keyboard list, where the voice keyboard is turned on, as Transcribro's StartScreen button does
 * https://developer.android.com/reference/android/provider/Settings#ACTION_INPUT_METHOD_SETTINGS
 */
fun openKeyboardSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (none: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Opens Android's voice input page, the general Settings when the phone has none.
 * https://developer.android.com/reference/android/provider/Settings#ACTION_VOICE_INPUT_SETTINGS
 */
fun openVoiceInputSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (none: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
