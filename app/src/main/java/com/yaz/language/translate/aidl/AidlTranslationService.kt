/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/AidlTranslationService.kt
 *
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Changed: the services come from Koin (the app's composition root) instead of TranslatorApplication; no Log at all
 * (upstream logged the text, OWASP MASVS-STORAGE-2); the caller must hold com.yaz.language.permission.TRANSLATE inside
 * every call, not only at bind; text over 50 000 characters is refused through the callback; a failure of the callback
 * (a client that died) is swallowed; the default target is the phone's language, then English (Language has no
 * default-target setting). The rest is as is.
 */
package com.yaz.language.translate.aidl

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.yaz.language.R
import com.yaz.language.translate.LanguageAvailabilityState
import com.yaz.language.translate.LanguageStateManager
import com.yaz.language.translate.TranslationCoordinator
import com.yaz.language.translate.TranslationResult
import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AidlTranslationService : Service() {
    private val coordinator: TranslationCoordinator by inject()
    private val states: LanguageStateManager by inject()

    // Callbacks are binder calls into the client; keep them off the main thread (upstream's comment, same scope).
    // Cancelled in onDestroy: the service lives only while a client is bound.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder =
        object : ITranslationService.Stub() {
            override fun translate(
                textToTranslate: String?,
                fromLanguageStr: String?,
                toLanguageStr: String?,
                callback: ITranslationCallback?,
            ) {
                // The binder thread: the caller's identity is only valid here, so it is checked before anything runs.
                val allowed =
                    TranslateGuards.callerAllowed(
                        callingUid = Binder.getCallingUid(),
                        ownUid = android.os.Process.myUid(),
                        permissionResult = checkCallingPermission(TranslateGuards.PERMISSION),
                    )
                if (!allowed) throw SecurityException("Not allowed")

                if (textToTranslate == null || callback == null) return

                if (!TranslateGuards.withinCap(textToTranslate)) {
                    fail(callback, ErrorType.UNEXPECTED, null, getString(R.string.aidl_text_too_long))
                    return
                }

                val catalog = states.catalog.value
                val fromLanguage = fromLanguageStr?.takeIf { it.isNotEmpty() }?.let { catalog.languageByCode(it) }
                val toLanguage = toLanguageStr?.takeIf { it.isNotEmpty() }?.let { catalog.languageByCode(it) }

                serviceScope.launch {
                    try {
                        val availability = LanguageAvailabilityState.of(catalog)
                        val langs = availability.sourceLanguages()
                        val from = fromLanguage ?: coordinator.detectLanguageRobust(textToTranslate, null, langs)
                        if (from == null) {
                            fail(callback, ErrorType.COULD_NOT_DETECT_LANGUAGE, null, null)
                            return@launch
                        }
                        if (!langs.contains(from)) {
                            fail(callback, ErrorType.DETECTED_BUT_UNAVAILABLE, from.displayName, null)
                            return@launch
                        }
                        val to =
                            toLanguage
                                ?: TranslateGuards.pickTarget(
                                    listOf(TranslateGuards.codeOf(Locale.getDefault()), "en"),
                                    availability.targetLanguages().map { it.code },
                                    from.code,
                                )?.let { catalog.languageByCode(it) }
                        if (to == null) {
                            fail(callback, ErrorType.UNEXPECTED, null, getString(R.string.aidl_target_unavailable))
                            return@launch
                        }
                        when (val result = coordinator.translateText(from, to, textToTranslate)) {
                            is TranslationResult.Success -> runCatching { callback.onTranslationResult(result.result.translated) }
                            is TranslationResult.Error -> fail(callback, ErrorType.UNEXPECTED, null, result.message)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        // No text in the message: the exception's class only.
                        fail(callback, ErrorType.UNEXPECTED, null, e.javaClass.simpleName)
                    }
                }
            }
        }

    /** The error through the callback; a client that is gone is no failure of ours. */
    private fun fail(callback: ITranslationCallback, type: Byte, language: String?, message: String?) {
        val err =
            TranslationError().apply {
                this.type = type
                this.language = language
                this.message = message
            }
        runCatching { callback.onTranslationError(err) }
    }

    // Upstream: bound only.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
