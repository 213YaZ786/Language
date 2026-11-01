package com.yaz.language.translate.aidl

import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

/**
 * The checks every way into the translation (the AIDL service, the text panel) goes through, as pure functions so a JVM
 * test can feed them hostile values.
 */
object TranslateGuards {
    /** Most characters one request translates (OWASP MASVS-CODE-4, input validation, https://mas.owasp.org/MASVS/). */
    const val MAX_CHARS = 50_000

    /** Permission a caller must hold, declared in the manifest with protectionLevel signature. */
    const val PERMISSION = "com.yaz.language.permission.TRANSLATE"

    /** Explicit intent extra the Models screen reads: true opens Models. */
    const val EXTRA_OPEN_MODELS = "com.yaz.language.extra.OPEN_MODELS"

    /** With [EXTRA_OPEN_MODELS], the code of the language to get (a String), when one is named. */
    const val EXTRA_MODELS_LANGUAGE = "com.yaz.language.extra.OPEN_MODELS_LANGUAGE"

    /** True when [text] may be translated: present and no longer than [MAX_CHARS]. */
    fun withinCap(text: CharSequence?): Boolean = text != null && text.length <= MAX_CHARS

    /**
     * The caller is allowed when it is this app itself, or holds [PERMISSION]. [permissionResult] is
     * Context.checkCallingPermission's answer, read on the binder thread (it is PERMISSION_DENIED outside a
     * transaction). The check inside the call guards every call, as the permission on the service guards the bind;
     * ContextImpl.checkCallingPermission
     * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java
     * and SMS's providers, which check inside call() (SMS core/mms/SavedDoor.kt).
     */
    fun callerAllowed(callingUid: Int, ownUid: Int, permissionResult: Int): Boolean =
        callingUid == ownUid || permissionResult == PackageManager.PERMISSION_GRANTED

    /** Incoming text clipped to [MAX_CHARS]; [clipped] tells the text was longer. */
    data class Clip(val text: String, val clipped: Boolean)

    /**
     * Text from an intent, cut at [MAX_CHARS] without splitting a surrogate pair; null when empty or blank. The text is
     * kept in memory only.
     */
    fun clip(text: CharSequence?): Clip? {
        if (text == null || text.isBlank()) return null
        if (text.length <= MAX_CHARS) return Clip(text.toString(), false)
        var end = MAX_CHARS
        if (Character.isHighSurrogate(text[end - 1])) end--
        return Clip(text.subSequence(0, end).toString(), true)
    }

    /**
     * Replace is offered only for a selection the caller lets change: ACTION_PROCESS_TEXT with
     * EXTRA_PROCESS_TEXT_READONLY false. A missing extra counts as read only, as offline-translator's
     * ProcessTextActivity reads it (getBooleanExtra(..., true)).
     */
    fun canReplace(action: String?, readOnlyExtra: Boolean): Boolean =
        action == Intent.ACTION_PROCESS_TEXT && !readOnlyExtra

    /**
     * The target language: the first of [preferred] (the phone's language, then English) that the phone can translate
     * to and that is not [from]; else any other target. Codes are the catalogue's.
     */
    fun pickTarget(preferred: List<String>, targets: List<String>, from: String): String? =
        preferred.firstOrNull { it in targets && it != from } ?: targets.firstOrNull { it != from }

    /** The catalogue's code for a locale ("zh_hant" for traditional Chinese, as Language.localizedName reads it). */
    fun codeOf(locale: Locale): String =
        if (locale.language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO"))) "zh_hant"
        else locale.language

    /** The language tag for a catalogue code (Language.localizedName's mapping). */
    fun tagOf(code: String): String = when (code) {
        "zh" -> "zh-Hans"
        "zh_hant" -> "zh-Hant"
        else -> code
    }
}
