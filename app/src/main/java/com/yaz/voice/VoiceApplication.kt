package com.yaz.voice

import android.app.Application
import com.yaz.voice.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Copied from Calculator's CalculatorApplication.kt (Koin started once, nothing in an isolated process)
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/CalculatorApplication.kt
 */
class VoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nothing of the app starts in an isolated process.
        if (android.os.Process.isIsolated()) return
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@VoiceApplication)
            modules(appModule)
        }
    }
}
