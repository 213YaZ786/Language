package com.yaz.language.di

import com.yaz.language.core.VoiceSpeech
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.feature.home.HomeViewModel
import com.yaz.language.recognize.RecognizeViewModel
import com.yaz.language.share.TranscribeViewModel
import com.yaz.language.translate.CatalogRepository
import com.yaz.language.translate.DownloadManager
import com.yaz.language.translate.FilePathManager
import com.yaz.language.translate.LanguageDetector
import com.yaz.language.translate.LanguageStateManager
import com.yaz.language.translate.TranslationCoordinator
import com.yaz.language.translate.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Scope of the translation core's downloads and state flows: lives with the process, nothing runs in the background. */
private val translateScope = named("translateScope")

/** Single composition root, as Calculator's di/AppModule.kt (https://github.com/213YaZ786/Calculator). */
val appModule = module {
    single { SettingsStore(androidContext()) }
    single { VoiceSpeech(androidContext(), get()) }
    viewModel { HomeViewModel(androidContext(), get()) }
    viewModel { RecognizeViewModel(androidContext(), get()) }
    viewModel { TranscribeViewModel(androidContext(), get()) }

    // Translation core, ported from offline-translator (DavidVentura/offline-translator v0.8.7,
    // app/src/main/java/dev/davidv/translator/TranslatorApplication.kt wires the same objects). Lazy: the native
    // library loads when a screen or the service first asks.
    single(translateScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { FilePathManager(androidContext()) }
    single { CatalogRepository.open(androidContext(), get()) }
    single { DownloadManager(get(translateScope), get(), get(), get()) }
    single {
        LanguageStateManager(get(translateScope), get()).also { it.connectToDownloadErrors(get<DownloadManager>().errors) }
    }
    single { TranslationService(get()) }
    single {
        val repository = get<CatalogRepository>()
        LanguageDetector { code -> repository.catalog.value.languageByCode(code) }
    }
    single { TranslationCoordinator(get(), get()) }
}
