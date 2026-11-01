/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/LanguageDetector.kt
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
 * Cut: the Log.d that printed the text to detect (OWASP MASVS-STORAGE-2: no sensitive data in logs).
 * The rest is as is.
 */
package com.yaz.language.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LanguageDetector(
    private val languageByCode: (String) -> Language?
) {
    private val nativeLanguageRuntime = NativeLanguageRuntime()

    private fun languageForCode(code: String?): Language? = code?.let(languageByCode)

    suspend fun detectLanguageRobust(
        text: String,
        hint: Language?,
        availableLanguages: List<Language>
    ): Language? =
        withContext(Dispatchers.IO) {
            languageForCode(
                nativeLanguageRuntime.detectLanguageRobustCode(
                    text,
                    hint?.code,
                    availableLanguages
                )
            )
        }
}
