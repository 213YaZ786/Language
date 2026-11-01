package com.yaz.language.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 with the wallpaper's colours, the same way as the other apps. Copied from Calculator's ui/theme/Theme.kt
 * (without its pure black and text size choices, which Language does not offer)
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/ui/theme/Theme.kt
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        // minSdk is 31, so dynamic colour is always available. The flag exists
        // for the fallback palette, should it ever be offered as a choice.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> AppDarkColors
        else -> AppLightColors
    }

    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
