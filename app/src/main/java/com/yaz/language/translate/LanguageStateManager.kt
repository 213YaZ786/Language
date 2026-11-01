/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/LanguageStateManager.kt
 *
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Cut: ocrFiles, dictionaryFiles and ttsFiles of the availability, sourceLanguages(requireOcr), deleteDict,
 * deleteTts, deleteTtsPack and deleteSupportByKind. Everything else is as is.
 */
package com.yaz.language.translate

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LanguageAvailabilityState(
    val hasLanguages: Boolean = false,
    val availableLanguages: List<LanguageAvailabilityEntry> = emptyList()
) {
    fun availabilityFor(language: Language?): LangAvailability? =
        language?.let { target ->
            availableLanguages.firstOrNull { it.language.code == target.code }?.availability
        }

    fun allLanguages(): List<Language> = availableLanguages.map { it.language }

    // Every installed pair pivots through English, so X is a source exactly when its X→en model is
    // in and a target exactly when its en→X model is. Some catalog languages ship only X→en, so
    // "installed" alone says nothing about which side a language can take. English needs no model
    // of its own, which makes it a source only once some en→X is installed and a target only once
    // some X→en is.
    private val englishIsSource = availableLanguages.any { !it.language.isEnglish && it.availability.hasFromEnglish }
    private val englishIsTarget = availableLanguages.any { !it.language.isEnglish && it.availability.hasToEnglish }

    private fun isSource(entry: LanguageAvailabilityEntry): Boolean =
        if (entry.language.isEnglish) englishIsSource else entry.availability.hasToEnglish

    private fun isTarget(entry: LanguageAvailabilityEntry): Boolean =
        if (entry.language.isEnglish) englishIsTarget else entry.availability.hasFromEnglish

    fun canBeSource(language: Language): Boolean = availableLanguages.any { it.language.code == language.code && isSource(it) }

    fun canBeTarget(language: Language): Boolean = availableLanguages.any { it.language.code == language.code && isTarget(it) }

    fun sourceLanguages(): List<Language> = availableLanguages.filter { isSource(it) }.map { it.language }

    fun targetLanguages(): List<Language> = availableLanguages.filter(::isTarget).map { it.language }

    companion object {
        fun of(catalog: LanguageCatalog): LanguageAvailabilityState {
            val availableLanguages = catalog.languageRows
            return LanguageAvailabilityState(
                hasLanguages = availableLanguages.any { !it.language.isEnglish && it.availability.translatorFiles },
                availableLanguages = availableLanguages
            )
        }
    }
}

class LanguageStateManager(
    private val scope: CoroutineScope,
    private val catalogRepository: CatalogRepository
) {
    val catalog: StateFlow<LanguageCatalog> = catalogRepository.catalog
    val languageState: StateFlow<LanguageAvailabilityState> =
        catalog
            .map(LanguageAvailabilityState::of)
            .stateIn(scope, SharingStarted.Eagerly, LanguageAvailabilityState.of(catalog.value))

    private val _fileEvents = MutableSharedFlow<FileEvent>()
    val fileEvents: SharedFlow<FileEvent> = _fileEvents.asSharedFlow()

    private var downloadErrorsJob: kotlinx.coroutines.Job? = null

    fun languageByCode(code: String): Language? = catalog.value.languageByCode(code)

    fun connectToDownloadErrors(downloadErrors: SharedFlow<String>) {
        downloadErrorsJob?.cancel()
        downloadErrorsJob =
            scope.launch {
                downloadErrors.collect { message ->
                    Log.w("LanguageStateManager", "Download error: $message")
                    _fileEvents.emit(FileEvent.Error(message))
                }
            }
    }

    // Install state can drift without the app touching it (files lost to storage issues), so
    // screens that surface it re-check the disk.
    fun refreshLanguageAvailability() {
        scope.launch(Dispatchers.IO) { catalogRepository.refresh() }
    }

    fun deleteLanguage(language: Language) {
        catalogRepository.applyDeletePlan(catalog.value.prepareDelete(language.code, Feature.CORE))
        Log.i("LanguageStateManager", "Removed language: ${language.displayName}")
    }

    fun canSwapLanguages(
        from: Language,
        to: Language
    ): Boolean = from != to && catalog.value.canTranslate(to, from)

    fun canTranslate(
        from: Language,
        to: Language
    ): Boolean {
        return catalog.value.canTranslate(from, to)
    }
}
