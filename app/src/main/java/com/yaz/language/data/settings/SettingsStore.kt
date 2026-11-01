package com.yaz.language.data.settings

import android.content.Context
import com.yaz.language.core.common.writeTextAtomically
import com.yaz.language.core.update.UpdateMode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app's choices. Fields as Calculator's data/settings/SettingsStore.kt keeps its own (updates, welcome seen);
 * the voice keyboard's four options are Transcribro's (auto start, auto stop, auto send, auto switch back)
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/preferences/PreferencesUiState.kt
 * with the defaults agreed for Language (auto start, auto stop and back to the previous keyboard on; auto send off).
 */
@Serializable
data class Settings(
    /** What happens when a newer version is out, checked once when the app opens. */
    val updates: UpdateMode = UpdateMode.INSTALL,
    /** The first launch page was closed. */
    val welcomed: Boolean = false,
    /** The speech model chosen: a model's file name, the phone's recognizer (SpeechModel.SYSTEM), or empty for the best one. */
    val speechModel: String = "",
    /** The model may be downloaded (the Network switch). */
    val networkAllowed: Boolean = true,
    /**
     * The translate screen's pair, kept as offline-translator keeps defaultTargetLanguageCode and
     * defaultSourceLanguageCode in its AppSettings (DavidVentura/offline-translator, app/src/main/java/dev/davidv/translator/AppSettings.kt):
     * a language code, empty for the phone's language (target) or Detect (source).
     */
    val targetLanguage: String = "",
    val sourceLanguage: String = "",
    /** The microphone right was asked for once: after that, a refusal without a question is a refusal for good. */
    val micAsked: Boolean = false,
    /** Voice keyboard: listening starts when the keyboard comes up. */
    val imeAutoStart: Boolean = true,
    /** Voice keyboard: listening ends when speech stops. */
    val imeAutoStop: Boolean = true,
    /** Voice keyboard: the words are sent to the field's action when speech stops. */
    val imeAutoSend: Boolean = false,
    /** Voice keyboard: the previous keyboard comes back when listening ends. */
    val imeSwitchBack: Boolean = true
)

/**
 * Small preference file, plain JSON written atomically, copied from Calculator's SettingsStore
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/data/settings/SettingsStore.kt
 * Nothing here is a secret.
 */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun load(): Settings {
        if (!file.exists()) return Settings()
        return runCatching { json.decodeFromString<Settings>(file.readText()) }
            .getOrDefault(Settings())
    }

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }
}
