package com.yaz.language

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yaz.language.navigation.LanguageApp
import com.yaz.language.ui.theme.AppSurface

/**
 * The one screen host, copied from Calculator's MainActivity.kt (edge to edge, AppSurface around the app)
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/MainActivity.kt
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppSurface { LanguageApp() } }
    }
}
