package com.yaz.voice.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yaz.voice.data.settings.SettingsStore
import com.yaz.voice.feature.home.HomeScreen
import com.yaz.voice.feature.settings.SettingsScreen
import com.yaz.voice.feature.welcome.WelcomeScreen
import org.koin.compose.koinInject

/** The app's places. */
object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/**
 * The app's three places in one NavHost, built as Calculator's navigation/CalculatorApp.kt: the welcome guide until it
 * is closed, then Home, with Settings on top; PlainBack after the NavHost, and the readable width on a wide window
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/navigation/CalculatorApp.kt
 */
@Composable
fun VoiceApp() {
    val nav = rememberNavController()
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()

    NavHost(nav, startDestination = if (settings.welcomed) Routes.HOME else Routes.WELCOME) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onStart = {
                store.update { it.copy(welcomed = true) }
                nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
            })
        }
        composable(Routes.HOME) {
            ReadableScroll { HomeScreen(onSettings = { nav.navigate(Routes.SETTINGS) }) }
        }
        composable(Routes.SETTINGS) {
            ReadableScroll { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
    PlainBack(nav)
}
