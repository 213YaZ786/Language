/*
 * Ported as is from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/FileEvent.kt
 * (not in the brief's list, but LanguageStateManager emits it.)
 */
package com.yaz.language.translate

sealed class FileEvent {
    data class Error(
        val message: String
    ) : FileEvent()
}
