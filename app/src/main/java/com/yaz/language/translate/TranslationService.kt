/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/TranslationService.kt
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
 * Cut: translateHtmlFragments (browser), translateDocumentPath and cancelDocumentTranslation (documents),
 * transliterate and its settings, the TransliterateBinding, and every Log call on a failure (OWASP MASVS-STORAGE-2:
 * the engine's failure reason can quote the text, so nothing about a translation is logged). The error text handed
 * back keeps the reason, as upstream. The constructor takes only the CatalogRepository. The rest is as is.
 */
package com.yaz.language.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.bindings.CatalogException

class TranslationService(
    private val catalogRepository: CatalogRepository
) {
    // / Requires the translation pairs to be available
    suspend fun preloadModel(
        from: Language,
        to: Language
    ) = withContext(Dispatchers.IO) {
        if (from == to) return@withContext

        val catalog = catalogRepository.catalog.value
        catalog.warmTranslationModels(from, to)
    }

    suspend fun translateMixedTexts(
        inputs: List<String>,
        forcedSourceLanguage: Language?,
        targetLanguage: Language,
        availableLanguages: List<Language>
    ): BatchTextTranslationOutput =
        withContext(Dispatchers.IO) {
            val catalog = catalogRepository.catalog.value

            val result =
                catalog.translateMixedTexts(inputs, forcedSourceLanguage, targetLanguage, availableLanguages)

            val nothingReason = result.nothingReason
            if (nothingReason != null && result.translations.isEmpty()) {
                return@withContext BatchTextTranslationOutput.NothingToTranslate(nothingReason)
            }

            val translatedByText = linkedMapOf<String, String>()
            result.translations.forEach { translation ->
                translatedByText[translation.sourceText] = translation.translatedText
            }
            BatchTextTranslationOutput.Translated(translatedByText)
        }

    suspend fun translate(
        from: Language,
        to: Language,
        text: String
    ): TranslationResult =
        withContext(Dispatchers.IO) {
            val catalog = catalogRepository.catalog.value
            val withAlternatives =
                try {
                    catalog.translateTextWithAlternatives(from, to, text)
                } catch (e: CatalogException.MissingAsset) {
                    return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
                } catch (e: CatalogException.Other) {
                    return@withContext TranslationResult.Error("Translation failed: ${e.message}")
                }
            TranslationResult.Success(
                TranslatedText(withAlternatives.translatedText, source = text, alternatives = withAlternatives.alternatives)
            )
        }

    /**
     * Re-translate [source] forcing [forcedPrefix] as the start of the output
     * (the confirmed target text up to and including a user-picked word), then
     * free-run the rest. Returns the steered translation with fresh alternatives.
     */
    suspend fun steer(
        from: Language,
        to: Language,
        source: String,
        forcedPrefix: String
    ): TranslationResult =
        withContext(Dispatchers.IO) {
            val catalog = catalogRepository.catalog.value
            val steered =
                try {
                    catalog.steer(from, to, source, forcedPrefix)
                } catch (e: CatalogException.MissingAsset) {
                    return@withContext TranslationResult.Error("Language pair ${from.code} -> ${to.code} not installed")
                } catch (e: CatalogException.Other) {
                    return@withContext TranslationResult.Error("Steer failed: ${e.message}")
                }
            TranslationResult.Success(
                TranslatedText(steered.translatedText, source = source, alternatives = steered.alternatives)
            )
        }
}

sealed class TranslationResult {
    data class Success(
        val result: TranslatedText
    ) : TranslationResult()

    data class Error(
        val message: String
    ) : TranslationResult()
}
