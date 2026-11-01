package com.yaz.language.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yaz.language.core.voice.Dictation
import com.yaz.language.core.voice.SpeechChoice
import com.yaz.language.core.voice.SpeechModel
import com.yaz.language.core.voice.Whisper
import com.yaz.language.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

/**
 * The speech model of the app, one for the whole app so a download goes on when the screen is left and every
 * dictation reads the same choice. Ported from our Keyboard app's KeyboardSpeech
 * (app/src/main/java/helium314/keyboard/latin/dictation/KeyboardSpeech.kt, a fork of https://github.com/HeliBorg/HeliBoard)
 * (the voice kit's SpeechModel, its choice kept in the app's settings through SpeechChoice, its download allowed only
 * while the Network switch is on, the app's page in Android's settings for a microphone refused for good), with the
 * language of the phone in place of the keyboard's subtype. Created by Koin when a screen first asks for it.
 */
class VoiceSpeech(context: Context, private val store: SettingsStore) {

    private val app = context.applicationContext

    val model: SpeechModel = SpeechModel(
        app,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        object : SpeechChoice {
            override var name: String
                get() = store.current.speechModel
                set(value) = store.update { it.copy(speechModel = value) }
        },
        language = { language() },
        allowed = { store.current.networkAllowed },
    )

    private var checkedFor: String = language().toLanguageTag()

    /** The language spoken: the phone's. */
    fun language(): Locale = Locale.getDefault()

    /**
     * Whether the phone's recognizer has to be asked again about the language: it was never asked for this one.
     * Said once per language. Ported from KeyboardSpeech.languageChanged.
     */
    private fun languageChanged(): Boolean {
        val now = language().toLanguageTag()
        if (now == checkedFor) return false
        checkedFor = now
        return true
    }

    /**
     * The engine a dictation runs on now, or null when none is ready (the screen then leads to the settings).
     * Ported from our Keyboard app's DictationController.start
     * (app/src/main/java/helium314/keyboard/latin/dictation/DictationController.kt, a fork of https://github.com/HeliBorg/HeliBoard)
     * (the phone's recognizer is asked about the language when that is not known yet; the phone's own when chosen
     * or when nothing else is chosen nor fetched; whisper when its library loaded and its model is on the phone).
     */
    suspend fun engine(): Dictation.Engine? {
        if (!model.system.value || languageChanged()) model.recheck().join()
        return when {
            model.usesSystem -> Dictation.Engine.SYSTEM
            Whisper.loaded && model.state.value == SpeechModel.State.Ready && model.file.exists() -> Dictation.Engine.WHISPER
            else -> null
        }
    }

    companion object {
        /**
         * The app's page in Android's settings, where the microphone can be allowed again after "don't ask again".
         * Ported from KeyboardSpeech.appSettingsIntent
         * https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS
         */
        fun appSettingsIntent(context: Context): Intent =
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
