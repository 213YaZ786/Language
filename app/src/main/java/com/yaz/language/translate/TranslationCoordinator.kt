/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/TranslationCoordinator.kt
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
 * Cut: the images and OCR paths (correctBitmap, translateImageWithOverlay, retranslateImageWithOverlay,
 * ProcessedImageResult), documents, transliteration, speech (SpeechService), the ImageProcessor and settings
 * dependencies, and the Log.d that timed each translation (it named the languages and the length of the text;
 * OWASP MASVS-STORAGE-2). The rest is as is.
 */
package com.yaz.language.translate

class TranslationCoordinator(
    private val translationService: TranslationService,
    private val languageDetector: LanguageDetector
) {
    suspend fun preloadModel(
        from: Language,
        to: Language
    ) = translationService.preloadModel(from, to)

    suspend fun translateText(
        from: Language,
        to: Language,
        text: String
    ): TranslationResult {
        if (text.isBlank()) return TranslationResult.Success(TranslatedText(""))
        return translationService.translate(from, to, text)
    }

    suspend fun steer(
        from: Language,
        to: Language,
        source: String,
        forcedPrefix: String
    ): TranslationResult = translationService.steer(from, to, source, forcedPrefix)

    suspend fun detectLanguageRobust(
        text: String,
        hint: Language?,
        availableLanguages: List<Language>
    ): Language? = languageDetector.detectLanguageRobust(text, hint, availableLanguages)
}
