package com.yaz.language.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.feature.home.HomeScreen
import com.yaz.language.feature.models.ModelsScreen
import com.yaz.language.feature.settings.SettingsScreen
import com.yaz.language.feature.welcome.WelcomeScreen
import org.koin.compose.koinInject

/** The app's places. */
object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val MODELS = "models"
}

/**
 * The app's three places in one NavHost, built as Calculator's navigation/CalculatorApp.kt: the welcome guide until it
 * is closed, then Home, with Settings on top; PlainBack after the NavHost, and the readable width on a wide window
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/navigation/CalculatorApp.kt
 */
@Composable
fun LanguageApp() {
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
            ReadableScroll {
                HomeScreen(
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onModels = { nav.navigate(Routes.MODELS) }
                )
            }
        }
        composable(Routes.MODELS) {
            ReadableScroll {
                ModelsScreen(onBack = { nav.popBackStack() }, onSettings = { nav.navigate(Routes.SETTINGS) })
            }
        }
        composable(Routes.SETTINGS) {
            ReadableScroll {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onModels = { nav.navigate(Routes.MODELS) },
                    // As Store's StoreApp.kt: the welcome route again, the home below it dropped (its end opens the home).
                    onGuide = { nav.navigate(Routes.WELCOME) { popUpTo(Routes.HOME) { inclusive = true } } }
                )
            }
        }
    }
    PlainBack(nav)
}
