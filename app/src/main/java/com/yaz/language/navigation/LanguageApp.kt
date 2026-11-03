package com.yaz.language.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun LanguageApp(request: ModelsRequest? = null, onHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val store: SettingsStore = koinInject()
    val settings by store.settings.collectAsState()
    // The language Models should show, from the text panel's "Get languages"; cleared once its row was shown.
    var focus by remember { mutableStateOf<String?>(null) }

    NavHost(nav, startDestination = if (settings.welcomed) Routes.HOME else Routes.WELCOME) {
        composable(Routes.WELCOME) {
            // Opened again from Settings once it was closed: the button reads Done and goes back. Read once on entry,
            // since closing the first guide sets welcomed while the screen is still leaving.
            val reopened = remember { settings.welcomed }
            WelcomeScreen(
                reopened = reopened,
                onLanguages = { nav.navigate(Routes.MODELS) },
                onStart = {
                    if (reopened) {
                        nav.popBackStack()
                    } else {
                        store.update { it.copy(welcomed = true) }
                        nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
                    }
                }
            )
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
                ModelsScreen(
                    onBack = { nav.popBackStack() },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    focus = focus,
                    onFocused = { focus = null }
                )
            }
        }
        composable(Routes.SETTINGS) {
            ReadableScroll {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onModels = { nav.navigate(Routes.MODELS) },
                    // The guide on top of Settings; its Done pops back here.
                    onGuide = { nav.navigate(Routes.WELCOME) }
                )
            }
        }
    }

    // Models opens on a request from outside (MainActivity.onCreate or onNewIntent), after the NavHost has its graph:
    // navigate with launchSingleTop, so a second request while Models is in front reuses it, as Navigation's
    // "Navigate to a destination" documents https://developer.android.com/guide/navigation/use-graph/navigate
    LaunchedEffect(request) {
        val asked = request ?: return@LaunchedEffect
        focus = asked.language
        nav.navigate(Routes.MODELS) { launchSingleTop = true }
        onHandled()
    }
    PlainBack(nav)
}

/**
 * A request to open Models: the catalogue code of the language to show, null for Models plainly. [token] makes two
 * requests for the same language different, so the second one is handled too.
 */
data class ModelsRequest(val language: String?, val token: Long)
