/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/TranslatedText.kt
 *
 * Cut: the `transliterated` field (transliteration is not ported). The rest is as is.
 */
package com.yaz.language.translate

data class TranslatedText(
    val translated: String,
    // Source text that produced this translation, needed to re-translate (steer)
    // when the user picks an alternative. Empty for paths that don't carry it.
    val source: String = "",
    // Per-word alternatives on the translated text (char offsets into `translated`).
    // Empty unless produced by the alternatives-bearing translation path.
    val alternatives: List<WordAlternatives> = emptyList()
)
