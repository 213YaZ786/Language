package com.yaz.voice.di

import com.yaz.voice.core.VoiceSpeech
import com.yaz.voice.data.settings.SettingsStore
import com.yaz.voice.feature.home.HomeViewModel
import com.yaz.voice.recognize.RecognizeViewModel
import com.yaz.voice.share.TranscribeViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Single composition root, as Calculator's di/AppModule.kt (https://github.com/213YaZ786/Calculator). */
val appModule = module {
    single { SettingsStore(androidContext()) }
    single { VoiceSpeech(androidContext(), get()) }
    viewModel { HomeViewModel(androidContext(), get()) }
    viewModel { RecognizeViewModel(androidContext(), get()) }
    viewModel { TranscribeViewModel(androidContext(), get()) }
}
