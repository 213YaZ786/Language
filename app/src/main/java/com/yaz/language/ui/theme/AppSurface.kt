package com.yaz.language.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import com.yaz.language.ui.glass.LocalGlass
import com.yaz.language.ui.glass.glassGround
import com.yaz.language.ui.glass.rememberGlassLook

/**
 * The frame every window of the app draws in: the system's theme, glass over Material You, and the page's ground with
 * its ambient light under everything. Copied from Calculator's ui/theme/AppSurface.kt (the theme follows the system,
 * glass is always on)
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/ui/theme/AppSurface.kt
 */
@Composable
fun ComponentActivity.AppSurface(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()

    // Status and navigation bar icons follow the app's theme, so they stay readable on the ground.
    DisposableEffect(dark) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        )
        onDispose { }
    }

    AppTheme(darkTheme = dark) {
        val look = rememberGlassLook(MaterialTheme.colorScheme, true, quiet = true)
        // Text and icons take the theme's colour in every window: without it
        // Compose draws them black, unreadable on a dark ground.
        CompositionLocalProvider(LocalGlass provides look, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().glassGround(look, MaterialTheme.colorScheme.background)) {
                content()
            }
        }
    }
}
