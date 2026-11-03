package com.yaz.language.screenshot

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.language.feature.home.GetItem
import com.yaz.language.feature.home.Heard
import com.yaz.language.feature.home.HomeContent
import com.yaz.language.feature.home.TranslateUi
import com.yaz.language.translate.DownloadStatus
import com.yaz.language.translate.Language
import com.yaz.language.translate.Script
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home as drawn on a phone and a tablet, by day and by night, with fixed data. Written as SMS's PersonPageScreenshotTest
 * https://github.com/213YaZ786/SMS/blob/main/app/src/test/java/com/yaz/sms/feature/thread/PersonPageScreenshotTest.kt
 * (the capture itself is ScreenshotBase's): empty, a translation, a language the phone lacks, the Network switch off, a
 * text cut at the cap. A block moved or cut in two fails the build.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the app's own starts Koin, none of the screen's business.
@Config(application = android.app.Application::class, sdk = [36])
class HomeScreenshotTest : ScreenshotBase() {

    private val english = Language("en", "English", "English", Script.LATIN)
    private val french = Language("fr", "French", "French", Script.LATIN)
    private val german = Language("de", "German", "German", Script.LATIN)
    private val arabic = Language("ar", "Arabic", "Arabic", Script.ARABIC)

    private val base = TranslateUi(
        languages = listOf(english, french, german, arabic),
        onPhone = setOf("en", "fr"),
        target = english
    )

    // The screen with nothing wired: a tap does nothing, the pictures show the state.
    @Composable
    private fun Home(ui: TranslateUi, input: String = "", clipped: Boolean = false) = HomeContent(
        ui = ui, input = input, heard = Heard(), clipped = clipped, hasClipboardText = true,
        speaking = false, noVoice = false,
        onModels = {}, onSettings = {}, onSource = {}, onTarget = {}, onSwap = {}, onInput = {}, onPaste = {},
        onMic = {}, onClear = {}, onDetected = {}, onCopy = {}, onListen = {}, onShare = {}, onGet = {}, onCancel = {}
    )

    @Test
    fun empty() = capture("home_empty") { Home(base) }

    @Test
    fun translation() = capture("home_translation") {
        Home(
            base.copy(detected = french, output = "Hello, how are you? I would like a table for two, please."),
            input = "Bonjour, comment allez-vous ? Je voudrais une table pour deux, s'il vous plaît."
        )
    }

    @Test
    fun languageToGet() = capture("home_language_to_get") {
        Home(
            base.copy(detected = arabic, toGet = listOf(GetItem(arabic, 31_000_000L, null))),
            input = "مرحبا، كيف حالك؟"
        )
    }

    @Test
    fun networkOff() = capture("home_network_off") {
        Home(
            base.copy(detected = arabic, toGet = listOf(GetItem(arabic, 31_000_000L, null)), networkAllowed = false),
            input = "مرحبا، كيف حالك؟"
        )
    }

    // The Get state running, taken from the cases above (https://github.com/213YaZ786/Language/blob/main/app/src/test/java/com/yaz/language/screenshot/HomeScreenshotTest.kt#HomeScreenshotTest)
    @Test
    fun getting() = capture("home_getting") {
        Home(
            base.copy(detected = arabic, toGet = listOf(GetItem(arabic, 31_000_000L, DownloadStatus.Running(done = 42L, total = 100L)))),
            input = "مرحبا، كيف حالك؟"
        )
    }

    @Test
    fun cutAtTheCap() = capture("home_cut_at_cap") {
        Home(
            base.copy(detected = french, output = "Hello, how are you?"),
            input = "Bonjour, comment allez-vous ?",
            clipped = true
        )
    }
}
