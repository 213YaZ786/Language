package com.yaz.language.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaz.language.data.settings.SettingsStore
import com.yaz.language.translate.DownloadId
import com.yaz.language.translate.DownloadManager
import com.yaz.language.translate.DownloadRequest
import com.yaz.language.translate.DownloadStatus
import com.yaz.language.translate.Language
import com.yaz.language.translate.LanguageStateManager
import com.yaz.language.translate.languageNameComparator
import com.yaz.language.translate.localizedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One catalogue language of the Models screen: whether it is on the phone, its size and its download. */
data class LanguageRow(
    val language: Language,
    val installed: Boolean,
    val bytes: Long,
    val status: DownloadStatus?
)

/**
 * The Models screen's languages, after offline-translator's language manager (v0.8.7, commit
 * e7716c6859e3d90b8883c667c1a3cfeef407df08, GPL-3.0, ui/screens/LanguageAssetManagerScreen.kt and
 * ui/components/LanguageDownloadButton.kt): the catalogue with each language's state from LanguageStateManager and
 * DownloadManager, a download started, cancelled or retried by DownloadRequest.Core, a delete by
 * LanguageStateManager.deleteLanguage, and the disk checked again when the screen opens ("install state can drift
 * without the app touching it", LanguageStateManager.refreshLanguageAvailability). Ordered as Google Translate's
 * "Downloaded languages" then the rest: English first, the languages on the phone, the others.
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/ui/screens/LanguageAssetManagerScreen.kt
 */
class ModelsViewModel(
    private val store: SettingsStore,
    private val states: LanguageStateManager,
    private val downloads: DownloadManager
) : ViewModel() {

    val rows: StateFlow<List<LanguageRow>> =
        combine(states.catalog, states.languageState, downloads.state) { catalog, availability, runs ->
            val comparator = languageNameComparator()
            availability.availableLanguages
                .map { entry ->
                    val language = entry.language
                    LanguageRow(
                        language = language,
                        installed = if (language.isEnglish) availability.hasLanguages else entry.availability.translatorFiles,
                        bytes = if (language.isEnglish) 0L else catalog.sizeBytesForFeature(language.code),
                        status = runs[DownloadId.Core(language)]
                    )
                }
                .sortedWith(
                    compareByDescending<LanguageRow> { it.language.isEnglish }
                        .thenByDescending { it.installed }
                        .thenBy(comparator) { it.language.localizedName() }
                )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        states.refreshLanguageAvailability()
    }

    /** Starts the download; refused here while the Network switch is off (DownloadManager refuses too). */
    fun get(language: Language) {
        if (store.current.networkAllowed) downloads.enqueue(DownloadRequest.Core(language))
    }

    fun cancel(language: Language) = downloads.cancel(DownloadId.Core(language))

    /** Removes the language's files; the disk work is off the main thread. */
    fun delete(language: Language) {
        viewModelScope.launch(Dispatchers.IO) { states.deleteLanguage(language) }
    }
}
