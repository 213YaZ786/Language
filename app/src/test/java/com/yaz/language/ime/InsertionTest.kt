package com.yaz.language.ime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words fitted to the text around them, on the cases of Transcribro's VoiceInput.onPartialResults (start of the
 * field, after a space, a newline, a selection replaced, words in capitals)
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/voiceinput/VoiceInput.kt
 * and of our Keyboard's dictation (the space before the words and the signs that join), as plain JUnit 4 tests
 * https://github.com/junit-team/junit4/wiki/Getting-started
 */
class InsertionTest {

    @Test
    fun startOfFieldKeepsTheWordsAsHeard() {
        assertEquals("Hello world", Insertion.words("Hello world", "", null))
        assertEquals("Hello world", Insertion.words("  Hello world ", "", null))
    }

    @Test
    fun nothingHeardAddsNothing() {
        assertEquals("", Insertion.words("   ", "a", null))
    }

    @Test
    fun afterASpaceNoSpaceAndTheSentenceGoesOn() {
        assertEquals("hello there", Insertion.words("Hello there", "me ", null))
    }

    @Test
    fun afterASignAndASpaceTheCapitalStays() {
        assertEquals("Hello there", Insertion.words("Hello there", ". ", null))
        assertEquals("Hello there", Insertion.words("Hello there", "e. ", null))
    }

    @Test
    fun afterAWordASpaceGoesBeforeAndTheFirstLetterIsLowered() {
        assertEquals(" and you", Insertion.words("And you", "me", null))
        assertEquals(" and you", Insertion.words("and you", "me", null))
    }

    @Test
    fun aSignThatJoinsTakesNoSpace() {
        assertEquals(", and you", Insertion.words(", and you", "me", null))
        assertEquals(".", Insertion.words(".", "me", null))
    }

    @Test
    fun afterAnOpeningSignNoSpace() {
        assertEquals("quoted", Insertion.words("quoted", "(", null))
        assertEquals("Quoted", Insertion.words("Quoted", "\"", null))
    }

    @Test
    fun afterANewlineANewSentenceBegins() {
        assertEquals("Hello", Insertion.words("Hello", "a\n", null))
        assertEquals("Hello", Insertion.words("Hello", "\n", null))
    }

    @Test
    fun oneCharacterBeforeIsEnough() {
        assertEquals(" one", Insertion.words("One", "a", null))
    }

    @Test
    fun aSelectionIsReplacedKeepingItsCapitalAndItsEndSign() {
        assertEquals("everyone", Insertion.words("Everyone.", "hello ", "world"))
        assertEquals("Everyone", Insertion.words("everyone", "", "Hello"))
        assertEquals("everyone,", Insertion.words("Everyone", "hello ", "world,"))
    }

    @Test
    fun aSelectionOfSignsOnlyBecomesWordsKeepingTheSigns() {
        assertEquals(" yes...", Insertion.words("Yes", "ok", "..."))
    }

    @Test
    fun wordsAllInCapitalsStayInCapitals() {
        assertEquals("OK", Insertion.words("OK", "me ", null))
        assertEquals(" OK", Insertion.words("OK", "me", null))
        assertEquals("OK", Insertion.words("OK", "", "ok"))
    }

    @Test
    fun anAcronymKeepsItsFirstLetterInTheMiddleOfASentence() {
        assertEquals("NASA launches", Insertion.words("NASA launches", "the ", null))
    }

    @Test
    fun languagesWithoutSpacesGetNone() {
        assertEquals("こんにちは", Insertion.words("こんにちは", "あ", null, spaces = false))
        assertFalse(Insertion.languageHasSpaces("ja"))
        assertTrue(Insertion.languageHasSpaces("fr"))
    }

    @Test
    fun passwordFieldsAreFound() {
        assertTrue(Insertion.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(Insertion.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(Insertion.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(Insertion.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(Insertion.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(Insertion.isPassword(InputType.TYPE_CLASS_TEXT))
        assertFalse(Insertion.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(Insertion.isPassword(InputType.TYPE_CLASS_NUMBER))
    }
}
