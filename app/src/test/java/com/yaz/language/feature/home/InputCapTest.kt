package com.yaz.language.feature.home

import com.yaz.language.translate.aidl.TranslateGuards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cap on Home's text field, with hostile sizes, in the way of TranslateGuardsTest (OWASP MASTG's input validation
 * tests feed the control what an attacker controls) https://mas.owasp.org/MASTG/
 */
class InputCapTest {
    private val max = TranslateGuards.MAX_CHARS

    @Test
    fun aTextAtTheLimitIsKept() {
        val text = "a".repeat(max)
        val capped = capInput(text)
        assertEquals(text, capped.text)
        assertFalse(capped.cut)
    }

    @Test
    fun oneCharacterMoreIsCut() {
        val capped = capInput("a".repeat(max + 1))
        assertEquals(max, capped.text.length)
        assertTrue(capped.cut)
    }

    @Test
    fun aSurrogatePairIsNeverSplit() {
        // A pair straddling the limit: the cut falls before it.
        val text = "a".repeat(max - 1) + "😀" + "b"
        val capped = capInput(text)
        assertEquals(max - 1, capped.text.length)
        assertTrue(capped.cut)
    }

    @Test
    fun emptyStaysEmptyAndNotCut() {
        val capped = capInput("")
        assertEquals("", capped.text)
        assertFalse(capped.cut)
    }

    @Test
    fun aHugeBlankTextReadsAsEmpty() {
        val capped = capInput(" ".repeat(max * 2))
        assertEquals("", capped.text)
        assertTrue(capped.cut)
    }
}
