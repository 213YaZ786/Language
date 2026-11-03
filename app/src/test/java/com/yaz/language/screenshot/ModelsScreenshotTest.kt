package com.yaz.language.screenshot

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.language.core.voice.SpeechModel
import com.yaz.language.feature.models.LanguageRow
import com.yaz.language.feature.models.ModelsActions
import com.yaz.language.feature.models.ModelsContent
import com.yaz.language.feature.models.SpeechUi
import com.yaz.language.translate.DownloadStatus
import com.yaz.language.translate.Language
import com.yaz.language.translate.Script
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Models screen as drawn on a phone and a tablet, by day and by night, with fixed data. Written as SMS's
 * PersonPageScreenshotTest
 * https://github.com/213YaZ786/SMS/blob/main/app/src/test/java/com/yaz/sms/feature/thread/PersonPageScreenshotTest.kt
 * (the capture itself is ScreenshotBase's): the speech section with nothing done and with everything done, and the
 * languages with English, one on the phone, one to get, one that failed, and the Network switch off.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class ModelsScreenshotTest : ScreenshotBase() {

    private fun language(code: String, name: String, script: Script = Script.LATIN) = Language(code, name, name, script)

    private val rows = listOf(
        LanguageRow(language("en", "English"), installed = true, bytes = 0L, status = null),
        LanguageRow(language("fr", "French"), installed = true, bytes = 31_000_000L, status = null),
        LanguageRow(language("ar", "Arabic", Script.ARABIC), installed = false, bytes = 33_500_000L, status = null),
        LanguageRow(language("de", "German"), installed = false, bytes = 30_200_000L, status = DownloadStatus.Failed("offline"))
    )

    private val todo = SpeechUi(
        micGranted = false, micRefusedForGood = false, voiceInput = false, voiceKeyboard = false,
        phoneRecognizer = false, usesSystem = false, pin = SpeechModel.BASE, state = SpeechModel.State.Missing, metered = false
    )

    private val done = SpeechUi(
        micGranted = true, micRefusedForGood = false, voiceInput = true, voiceKeyboard = true,
        phoneRecognizer = true, usesSystem = false, pin = SpeechModel.SMALL, state = SpeechModel.State.Ready, metered = false
    )

    // The Whisper model on its way, at 42 %.
    private val fetching = SpeechUi(
        micGranted = true, micRefusedForGood = false, voiceInput = true, voiceKeyboard = true,
        phoneRecognizer = false, usesSystem = false, pin = SpeechModel.BASE, state = SpeechModel.State.Fetching(0.42f), metered = false
    )

    // Arabic downloading, at 60 %.
    private val rowsGetting = rows.map {
        if (it.language.code == "ar") it.copy(status = DownloadStatus.Running(done = 60L, total = 100L)) else it
    }

    // The screen with nothing wired: a tap does nothing, the pictures show the state.
    @Composable
    private fun Models(speech: SpeechUi, networkAllowed: Boolean = true, rows: List<LanguageRow> = this.rows) =
        ModelsContent(speech = speech, rows = rows, networkAllowed = networkAllowed, focus = null, actions = ModelsActions())

    @Test
    fun toDo() = capture("models_to_do") { Models(todo) }

    @Test
    fun allDone() = capture("models_all_done") { Models(done) }

    @Test
    fun networkOff() = capture("models_network_off") { Models(todo, networkAllowed = false) }

    // A running download, taken from this test's own cases above (https://github.com/213YaZ786/Language/blob/main/app/src/test/java/com/yaz/language/screenshot/ModelsScreenshotTest.kt#ModelsScreenshotTest)
    @Test
    fun fetching() = capture("models_fetching") { Models(fetching, rows = rowsGetting) }
}
