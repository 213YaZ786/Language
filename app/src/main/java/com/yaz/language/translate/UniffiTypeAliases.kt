/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/UniffiTypeAliases.kt
 *
 * Cut: the aliases for OCR, reading order, writing systems, TTS and overlays, singleScript() and
 * BackgroundMode.labelRes (they belong to features Language does not have). Kept as is: the aliases text
 * translation, pack plans and deletion use.
 */
package com.yaz.language.translate

typealias Script = uniffi.translator_core.Script
typealias NothingReason = uniffi.translator_translate.NothingReason
typealias TokenAlignment = uniffi.translator_translate.TokenAlignment
typealias TranslationWithAlignment = uniffi.translator_translate.TranslationWithAlignment
typealias WordAlternative = uniffi.translator_translate.WordAlternative
typealias WordAlternatives = uniffi.translator_translate.WordAlternatives
typealias TranslationWithAlternatives = uniffi.translator_translate.TranslationWithAlternatives
typealias Feature = uniffi.translator.Feature
typealias DownloadPlan = uniffi.translator_core.DownloadPlan
typealias DownloadTask = uniffi.translator_core.DownloadTask
typealias DeletePlan = uniffi.translator_core.DeletePlan
