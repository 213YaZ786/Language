package com.yaz.language.screenshot

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.language.processtext.PanelSheet
import com.yaz.language.processtext.Ui
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The text panel ("Translate" in an app's text selection) as drawn on a phone and a tablet, by day and by night, with
 * fixed data. Written as SMS's PersonPageScreenshotTest
 * https://github.com/213YaZ786/SMS/blob/main/app/src/test/java/com/yaz/sms/feature/thread/PersonPageScreenshotTest.kt
 * (the capture itself is ScreenshotBase's): a translation with Replace, one cut at the cap, a language to get, a text
 * not recognised, a failure.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class PanelScreenshotTest : ScreenshotBase() {

    private val done = Ui.Done(
        from = "French", to = "English", toCode = "en",
        translated = "Hello, how are you? I would like a table for two, please.",
        source = "Bonjour, comment allez-vous ? Je voudrais une table pour deux, s'il vous plaît."
    )

    // Shown from the first frame: the slide in is the activity's, not the content's.
    @Composable
    private fun Panel(state: Ui, clipped: Boolean = false, canReplace: Boolean = false) = PanelSheet(
        shown = MutableTransitionState(true), state = state, note = null, clipped = clipped, canReplace = canReplace,
        onCopy = {}, onReplace = {}, onShare = {}, onListen = {}, onOpen = {}, onCancel = {}
    )

    @Test
    fun translated() = capture("panel_translated") { Panel(done, canReplace = true) }

    @Test
    fun cutAtTheCap() = capture("panel_cut_at_cap") { Panel(done, clipped = true) }

    @Test
    fun languageToGet() = capture("panel_language_to_get") { Panel(Ui.Missing("Arabic", "ar")) }

    @Test
    fun notRecognised() = capture("panel_not_recognised") { Panel(Ui.NotRecognised) }

    @Test
    fun failed() = capture("panel_failed") { Panel(Ui.Failed) }
}
