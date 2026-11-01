package com.yaz.language.translate.aidl

import android.content.pm.PackageManager
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile inputs for the translation service's guards, in the way of PackChecksTest (offline-translator has no JVM tests
 * for its service): OWASP MASTG's input validation tests feed the control what an attacker controls, here the size of a
 * text, who calls, and the extras of an intent https://mas.owasp.org/MASTG/
 */
class TranslateGuardsTest {
    private val granted = PackageManager.PERMISSION_GRANTED
    private val denied = PackageManager.PERMISSION_DENIED

    @Test
    fun capAcceptsTheLimitAndRefusesOneMore() {
        assertTrue(TranslateGuards.withinCap("a".repeat(TranslateGuards.MAX_CHARS)))
        assertFalse(TranslateGuards.withinCap("a".repeat(TranslateGuards.MAX_CHARS + 1)))
        assertFalse(TranslateGuards.withinCap(null))
        assertTrue(TranslateGuards.withinCap(""))
    }

    @Test
    fun callerWithoutThePermissionIsRefused() {
        assertFalse(TranslateGuards.callerAllowed(callingUid = 10123, ownUid = 10050, permissionResult = denied))
    }

    @Test
    fun callerWithThePermissionIsAllowed() {
        assertTrue(TranslateGuards.callerAllowed(callingUid = 10123, ownUid = 10050, permissionResult = granted))
    }

    @Test
    fun ownUidIsAllowed() {
        assertTrue(TranslateGuards.callerAllowed(callingUid = 10050, ownUid = 10050, permissionResult = denied))
    }

    @Test
    fun anyOtherResultThanGrantedIsRefused() {
        assertFalse(TranslateGuards.callerAllowed(callingUid = 1, ownUid = 2, permissionResult = -2))
        assertFalse(TranslateGuards.callerAllowed(callingUid = 1, ownUid = 2, permissionResult = 1))
    }

    @Test
    fun clipKeepsAShortTextAsIs() {
        val clip = TranslateGuards.clip("Bonjour")
        assertNotNull(clip)
        assertEquals("Bonjour", clip!!.text)
        assertFalse(clip.clipped)
    }

    @Test
    fun clipRefusesEmptyAndBlank() {
        assertNull(TranslateGuards.clip(null))
        assertNull(TranslateGuards.clip(""))
        assertNull(TranslateGuards.clip("  \n\t"))
    }

    @Test
    fun clipCutsAtTheCap() {
        val clip = TranslateGuards.clip("a".repeat(TranslateGuards.MAX_CHARS + 500))!!
        assertEquals(TranslateGuards.MAX_CHARS, clip.text.length)
        assertTrue(clip.clipped)
    }

    @Test
    fun clipNeverSplitsASurrogatePair() {
        // A pair that straddles the cap: its high half is the last char kept, so it goes too.
        val text = "a".repeat(TranslateGuards.MAX_CHARS - 1) + "😀" + "b"
        val clip = TranslateGuards.clip(text)!!
        assertEquals(TranslateGuards.MAX_CHARS - 1, clip.text.length)
        assertFalse(Character.isHighSurrogate(clip.text.last()))
        assertTrue(clip.clipped)
    }

    @Test
    fun replaceOnlyForAWritableProcessText() {
        assertTrue(TranslateGuards.canReplace(android.content.Intent.ACTION_PROCESS_TEXT, readOnlyExtra = false))
        assertFalse(TranslateGuards.canReplace(android.content.Intent.ACTION_PROCESS_TEXT, readOnlyExtra = true))
        assertFalse(TranslateGuards.canReplace(android.content.Intent.ACTION_SEND, readOnlyExtra = false))
        assertFalse(TranslateGuards.canReplace(null, readOnlyExtra = false))
    }

    @Test
    fun targetIsThePhoneLanguageThenEnglishThenAnyOther() {
        assertEquals("fr", TranslateGuards.pickTarget(listOf("fr", "en"), listOf("en", "fr", "de"), from = "de"))
        assertEquals("en", TranslateGuards.pickTarget(listOf("fr", "en"), listOf("en", "de"), from = "de"))
        assertEquals("de", TranslateGuards.pickTarget(listOf("fr", "en"), listOf("de", "es"), from = "es"))
        assertNull(TranslateGuards.pickTarget(listOf("fr", "en"), emptyList(), from = "de"))
    }

    @Test
    fun targetIsNeverTheSourceLanguage() {
        assertEquals("en", TranslateGuards.pickTarget(listOf("fr", "en"), listOf("fr", "en"), from = "fr"))
        assertNull(TranslateGuards.pickTarget(listOf("fr", "en"), listOf("fr"), from = "fr"))
    }

    @Test
    fun codesFollowTheCatalogue() {
        assertEquals("fr", TranslateGuards.codeOf(Locale.FRANCE))
        assertEquals("zh_hant", TranslateGuards.codeOf(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh", TranslateGuards.codeOf(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant", TranslateGuards.tagOf("zh_hant"))
        assertEquals("zh-Hans", TranslateGuards.tagOf("zh"))
        assertEquals("fr", TranslateGuards.tagOf("fr"))
    }
}
