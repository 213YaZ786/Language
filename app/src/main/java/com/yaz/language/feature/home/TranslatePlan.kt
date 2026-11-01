package com.yaz.language.feature.home

import com.yaz.language.translate.Language
import com.yaz.language.translate.LanguageAvailabilityState
import java.util.Locale

/*
 * Pure rules of the translate screen, kept apart from the ViewModel so they run in unit tests.
 *
 * LanguageDetection and refreshedDetection are ported from offline-translator v0.8.7 (commit
 * e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0, ui/TranslationSession.kt:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/ui/TranslationSession.kt
 */

/**
 * A text detection only stays valid while the input is at least as long as the text that produced it; deleting below
 * that point drops the detection so a stale suggestion can't outlive the words it was based on.
 * Ported from offline-translator's ui/TranslationSession.kt (LanguageDetection), as is.
 */
internal data class LanguageDetection(
    val language: Language,
    val inputLength: Int
)

/** Ported from offline-translator's ui/TranslationSession.kt (refreshedDetection), as is. */
internal fun refreshedDetection(
    prior: LanguageDetection?,
    text: String,
    detected: Language?
): LanguageDetection? =
    when {
        text.isBlank() -> null
        detected != null -> LanguageDetection(detected, text.length)
        else -> prior?.takeIf { text.length >= it.inputLength }
    }

/**
 * The languages whose pack a pair still needs. Every pair pivots through English, so a source needs its X to English
 * model and a target its English to X model, and English needs no pack of its own: the same reading of
 * LanguageAvailabilityState.isSource / isTarget in offline-translator's LanguageStateManager.kt
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/LanguageStateManager.kt
 * Unlike offline-translator, whose pickers offer installed languages only, Language's pickers offer the whole
 * catalogue (Google Translate's list), so a pair can name a language that is not on the phone.
 */
fun languagesToGet(availability: LanguageAvailabilityState, from: Language, to: Language): List<Language> =
    buildList {
        if (!from.isEnglish && availability.availabilityFor(from)?.hasToEnglish != true) add(from)
        if (!to.isEnglish && availability.availabilityFor(to)?.hasFromEnglish != true) add(to)
    }.distinct()

/**
 * The locale of a catalogue language, with the same tags as offline-translator's Language.localizedName
 * (translate/Language.kt): "zh" is Simplified, "zh_hant" Traditional.
 */
fun Language.locale(): Locale = Locale.forLanguageTag(
    when (code) {
        "zh" -> "zh-Hans"
        "zh_hant" -> "zh-Hant"
        else -> code
    }
)

/** Words joined with one space, for the dictation added after the text already typed. */
internal fun joinWords(before: String, spoken: String): String =
    when {
        before.isBlank() -> spoken
        spoken.isBlank() -> before
        before.last().isWhitespace() -> before + spoken
        else -> "$before $spoken"
    }
