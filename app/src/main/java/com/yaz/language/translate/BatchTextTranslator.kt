/*
 * Ported as is from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/BatchTextTranslator.kt
 */
package com.yaz.language.translate

sealed class BatchTextTranslationOutput {
    data class Translated(
        val results: LinkedHashMap<String, String>
    ) : BatchTextTranslationOutput()

    data class NothingToTranslate(
        val reason: NothingReason
    ) : BatchTextTranslationOutput()
}
