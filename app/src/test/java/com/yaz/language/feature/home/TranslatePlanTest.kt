package com.yaz.language.feature.home

import com.yaz.language.translate.LangAvailability
import com.yaz.language.translate.Language
import com.yaz.language.translate.LanguageAvailabilityEntry
import com.yaz.language.translate.LanguageAvailabilityState
import com.yaz.language.translate.Script
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Tests of the translate screen's pair rules, in the style of offline-translator's MainSelectionRulesTest.kt
 * (a catalogue built by hand, the rule asked, the answer compared), v0.8.7, GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/test/java/dev/davidv/translator/MainSelectionRulesTest.kt
 */
class TranslatePlanTest {

    private fun lang(code: String, name: String = code) = Language(code, name, name, Script.LATIN)

    private val en = lang("en", "English")
    private val fr = lang("fr", "French")
    private val de = lang("de", "German")
    private val ja = lang("ja", "Japanese")

    private fun state(vararg entries: Pair<Language, LangAvailability>) = LanguageAvailabilityState(
        hasLanguages = entries.any { !it.first.isEnglish && it.second.translatorFiles },
        availableLanguages = entries.map { LanguageAvailabilityEntry(it.first, it.second) }
    )

    private val both = LangAvailability(hasFromEnglish = true, hasToEnglish = true)
    private val none = LangAvailability(hasFromEnglish = false, hasToEnglish = false)

    @Test
    fun `nothing to get when both ends are on the phone`() {
        val s = state(en to none, fr to both, de to both)
        assertEquals(emptyList<Language>(), languagesToGet(s, fr, de))
    }

    @Test
    fun `English needs no pack of its own`() {
        val s = state(en to none, fr to both)
        assertEquals(emptyList<Language>(), languagesToGet(s, en, fr))
        assertEquals(emptyList<Language>(), languagesToGet(s, fr, en))
    }

    @Test
    fun `a missing source and a missing target are both named`() {
        val s = state(en to none, fr to none, ja to none)
        assertEquals(listOf(fr, ja), languagesToGet(s, fr, ja))
    }

    @Test
    fun `a source needs its model to English, a target its model from English`() {
        val toEnglishOnly = LangAvailability(hasFromEnglish = false, hasToEnglish = true)
        val s = state(en to none, fr to toEnglishOnly, de to toEnglishOnly)
        assertEquals(emptyList<Language>(), languagesToGet(s, fr, en))
        assertEquals(listOf(de), languagesToGet(s, fr, de))
    }

    @Test
    fun `a language missing from the catalogue is asked for`() {
        val s = state(en to none)
        assertEquals(listOf(fr), languagesToGet(s, fr, en))
    }

    @Test
    fun `detection is kept while the text is as long as when it was found`() {
        val prior = LanguageDetection(fr, 10)
        assertEquals(prior, refreshedDetection(prior, "x".repeat(12), null))
        assertNull(refreshedDetection(prior, "x".repeat(9), null))
        assertNull(refreshedDetection(prior, "  ", fr))
        assertEquals(LanguageDetection(de, 3), refreshedDetection(prior, "abc", de))
    }

    @Test
    fun `dictated words follow the typed text with one space`() {
        assertEquals("hello world", joinWords("hello", "world"))
        assertEquals("hello world", joinWords("hello ", "world"))
        assertEquals("world", joinWords("", "world"))
        assertEquals("hello", joinWords("hello", " "))
    }

    @Test
    fun `Chinese keeps its script in the locale`() {
        assertEquals(Locale.forLanguageTag("zh-Hant"), lang("zh_hant").locale())
        assertEquals(Locale.forLanguageTag("zh-Hans"), lang("zh").locale())
        assertEquals(Locale.forLanguageTag("fr"), fr.locale())
    }

    @Test
    fun `long speech is cut after whitespace and nothing is lost`() {
        val text = "alpha beta gamma delta epsilon"
        val parts = Speaker.chunks(text, 12)
        assertEquals(text, parts.joinToString(""))
        assertEquals(true, parts.all { it.length <= 12 })
        assertEquals(listOf("short"), Speaker.chunks("short", 12))
    }
}
