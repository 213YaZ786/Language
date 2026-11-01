package com.yaz.language.screenshot

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yaz.language.core.update.UpdateMode
import com.yaz.language.data.settings.Settings
import com.yaz.language.feature.settings.SettingsContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings as drawn on a phone and a tablet, by day and by night, with fixed settings. Written as SMS's
 * PersonPageScreenshotTest
 * https://github.com/213YaZ786/SMS/blob/main/app/src/test/java/com/yaz/sms/feature/thread/PersonPageScreenshotTest.kt
 * (the capture itself is ScreenshotBase's): as it opens (the sections folded on a phone, the first one's page beside the
 * list on a tablet), then each of the two other sections opened by a tap on its row, as Compose's testing guide taps
 * a node found by its text https://developer.android.com/develop/ui/compose/testing/interactions
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [36])
class SettingsScreenshotTest : ScreenshotBase() {

    private val settings = Settings(updates = UpdateMode.NOTIFY, imeAutoSend = true, imeSwitchBack = false)

    private fun screen(name: String, open: String? = null) = capture(name, interact = {
        if (open != null) {
            compose.onNodeWithText(open).performClick()
            compose.waitForIdle()
        }
    }) {
        SettingsContent(
            settings = settings, version = "0.1.1",
            onBack = {}, onGuide = {}, onModels = {}, onChange = {}, onUpdates = {}, onOpen = {}
        )
    }

    @Test
    fun opened() = screen("settings")

    @Test
    fun voiceKeyboard() = screen("settings_voice_keyboard", open = "Voice keyboard")

    @Test
    fun about() = screen("settings_about", open = "About")
}
