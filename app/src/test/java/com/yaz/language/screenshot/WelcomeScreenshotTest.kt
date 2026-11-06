package com.yaz.language.screenshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.language.feature.welcome.WelcomeContent
import com.yaz.language.ui.component.SetupTile
import com.yaz.language.ui.icon.AppIcons
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome guide as drawn on a phone and a tablet, by day and by night, with fixed tiles. Written as the Models
 * screen's test
 * https://github.com/213YaZ786/Language/blob/main/app/src/test/java/com/yaz/language/screenshot/ModelsScreenshotTest.kt#ModelsScreenshotTest
 * (the capture itself is ScreenshotBase's): the first run, with a speech engine being fetched, and everything set,
 * reopened from Settings.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class WelcomeScreenshotTest : ScreenshotBase() {

    private fun tile(
        id: String, name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, state: String,
        done: Boolean, required: Boolean, error: Boolean = false, progress: Float? = null
    ) = SetupTile(
        id = id, name = name, title = name, icon = icon, state = state, summary = "", done = done,
        required = required, error = error, progress = progress, onClick = {}
    )

    // The first run: the microphone allowed, the engine on its way, the rest still to do.
    private val firstRun = listOf(
        tile("microphone", "Microphone", AppIcons.Mic, "Allowed", done = true, required = true),
        tile("engine", "Speech engine", AppIcons.Memory, "42 %", done = false, required = true, progress = 0.42f),
        tile("voiceInput", "Voice input", AppIcons.GraphicEq, "Not chosen", done = false, required = true),
        tile("languages", "Languages", AppIcons.Translate, "French", done = true, required = false)
    )

    // Everything done, the guide reopened from Settings.
    private val allSet = listOf(
        tile("microphone", "Microphone", AppIcons.Mic, "Allowed", done = true, required = true),
        tile("engine", "Speech engine", AppIcons.Memory, "Whisper Base, 148 MB", done = true, required = true),
        tile("voiceInput", "Voice input", AppIcons.GraphicEq, "Language", done = true, required = true),
        tile("languages", "Languages", AppIcons.Translate, "French", done = true, required = false)
    )

    @Test
    fun firstRun() = capture("welcome_first_run") { WelcomeContent(firstRun, reopened = false, onStart = {}) }

    @Test
    fun allSet() = capture("welcome_all_set") { WelcomeContent(allSet, reopened = true, onStart = {}) }
}
