/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/DownloadManager.kt
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
 * Cut: the requests for dictionaries, text to speech, OCR, upgrades, support files and the catalogue download
 * (fetchCatalog: Language never fetches a catalogue), the per-language TTS locks, the adblock reload, the Context
 * and the string resources. No foreground service (DownloadService is not ported): a download runs in the
 * app-scoped coroutine only while the process lives.
 * Changed: the Network switch (SettingsStore.networkAllowed) replaces SettingsManager; a download is refused with
 * a clear message while it is off. Failure messages are plain English here; the screens translate them later.
 * The rest (runs, published state, errors flow) is as is.
 */
package com.yaz.language.translate

import android.util.Log
import com.yaz.language.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val TAG = "DownloadManager"

class DownloadManager(
    private val scope: CoroutineScope,
    private val catalogRepository: CatalogRepository,
    filePathManager: FilePathManager,
    private val settingsStore: SettingsStore
) {
    private val installer = PackInstaller(filePathManager, catalogRepository) { settingsStore.current.networkAllowed }

    // Guards runs and every state write. A run may only touch its id while it is still the Job
    // registered there, so a cancelled or superseded run can neither publish nor remove a newer one.
    private val lock = Any()
    private val runs = mutableMapOf<DownloadId, Job>()
    private val published = MutableStateFlow<Map<DownloadId, DownloadStatus>>(emptyMap())
    val state: StateFlow<Map<DownloadId, DownloadStatus>> = published.asStateFlow()

    private val failures = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = failures.asSharedFlow()

    fun enqueue(request: DownloadRequest) {
        synchronized(lock) {
            if (request.id in runs) return
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(request) }
            runs[request.id] = job
            published.value += request.id to DownloadStatus.Queued
            job.start()
        }
    }

    fun cancel(id: DownloadId) {
        synchronized(lock) {
            runs.remove(id)?.cancel()
            published.value -= id
        }
        Log.i(TAG, "Cancelled $id")
    }

    private suspend fun execute(request: DownloadRequest) {
        val run = currentCoroutineContext().job
        try {
            install(request, run)
            Log.i(TAG, "Completed ${request.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed ${request.id}", e)
            fail(request.id, run, failureMessage(request, e))
        } finally {
            synchronized(lock) {
                if (runs[request.id] === run) {
                    runs.remove(request.id)
                    published.value -= request.id
                }
            }
        }
    }

    private suspend fun install(
        request: DownloadRequest,
        run: Job
    ) {
        val plan = planFor(request, catalogRepository.catalog.value) ?: error("Catalog has nothing to download for $request")
        publish(request.id, run, DownloadStatus.Running(0, plan.totalSize.toLong()))
        installer.installPlan(plan) { bytes -> addProgress(request.id, run, bytes) }
    }

    private fun planFor(
        request: DownloadRequest,
        catalog: LanguageCatalog
    ): DownloadPlan? =
        when (request) {
            is DownloadRequest.Core -> catalog.planDownload(request.language.code, Feature.CORE)
            DownloadRequest.Repair -> catalog.planRepair()
        }

    private fun failureMessage(
        request: DownloadRequest,
        error: Exception
    ): String {
        val what =
            when (request) {
                is DownloadRequest.Core -> "Could not download ${request.language.displayName}"
                DownloadRequest.Repair -> "Could not repair the languages"
            }
        return when (error) {
            is NetworkNotAllowedException -> "$what. Network is off in Language's settings, turn it on to download."
            is PackSecurityException -> "$what. ${error.message}."
            else -> "$what."
        }
    }

    private fun publish(
        id: DownloadId,
        run: Job,
        status: DownloadStatus
    ) {
        synchronized(lock) {
            if (runs[id] !== run) return
            published.value += id to status
        }
    }

    private fun addProgress(
        id: DownloadId,
        run: Job,
        bytes: Long
    ) {
        synchronized(lock) {
            if (runs[id] !== run) return
            val running = published.value.getValue(id) as DownloadStatus.Running
            published.value += id to running.copy(done = running.done + bytes)
        }
    }

    private suspend fun fail(
        id: DownloadId,
        run: Job,
        message: String
    ) {
        synchronized(lock) {
            if (runs[id] !== run) return
            runs.remove(id)
            published.value += id to DownloadStatus.Failed(message)
        }
        failures.emit(message)
    }
}
