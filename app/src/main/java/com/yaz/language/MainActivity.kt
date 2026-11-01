package com.yaz.language

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yaz.language.navigation.LanguageApp
import com.yaz.language.navigation.ModelsRequest
import com.yaz.language.translate.LanguageStateManager
import com.yaz.language.translate.aidl.TranslateGuards
import com.yaz.language.ui.theme.AppSurface
import org.koin.android.ext.android.inject

/**
 * The one screen host, copied from Calculator's MainActivity.kt (edge to edge, AppSurface around the app)
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/MainActivity.kt
 *
 * It is a singleTask activity, so a second launch (the text panel's "Get languages") arrives in [onNewIntent] and not
 * in a new instance: Android's "Tasks and the back stack"
 * https://developer.android.com/guide/components/activities/tasks-and-back-stack ("Handling affinities" and the
 * singleTask mode: the system routes the new intent to onNewIntent of the instance that exists). Both entry points
 * read the same extras, [TranslateGuards.EXTRA_OPEN_MODELS] and [TranslateGuards.EXTRA_MODELS_LANGUAGE], and hand the
 * request to LanguageApp, which opens Models.
 */
class MainActivity : ComponentActivity() {

    private val states: LanguageStateManager by inject()
    private var request by mutableStateOf<ModelsRequest?>(null)
    private var requests = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only a fresh launch: a recreated activity (saved state) or one reopened from recents has been through this.
        if (savedInstanceState == null) request = modelsRequest(intent)
        setContent { AppSurface { LanguageApp(request = request, onHandled = { request = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = modelsRequest(intent)
    }

    /**
     * The request an intent carries, or null. The extras are removed once read, so the intent Android keeps for this
     * task (a rotation, recents) cannot ask again; a launch from recents is ignored. The language is accepted only
     * when the catalogue has it, anything else opens Models plainly (the intent comes from outside: this activity is
     * exported).
     */
    private fun modelsRequest(intent: Intent): ModelsRequest? {
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val open = runCatching { intent.getBooleanExtra(TranslateGuards.EXTRA_OPEN_MODELS, false) }.getOrDefault(false)
        val code = runCatching { intent.getStringExtra(TranslateGuards.EXTRA_MODELS_LANGUAGE) }.getOrNull()
        intent.removeExtra(TranslateGuards.EXTRA_OPEN_MODELS)
        intent.removeExtra(TranslateGuards.EXTRA_MODELS_LANGUAGE)
        if (!open || fromHistory) return null
        val known = code?.takeIf { states.catalog.value.languageByCode(it) != null }
        return ModelsRequest(known, ++requests)
    }
}
