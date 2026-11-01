/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/PackInstaller.kt
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
 * Cut: zip extraction (extractZip, planZipExtraction, replacePath, keepDirectory, writeMarker) and gunzip: a task
 * that needs either is refused (OWASP MASVS-CODE-4; the catalogue has none), and downloadFile (it fetched the
 * catalogue). StagedInstall keeps only files.
 * Changed (the checks are in PackChecks.kt, each citing its source):
 * - every file's size and SHA-256 are checked against the bundled catalogue before it leaves the staging folder
 *   (written to "<name>.part", verified, atomically renamed), a mismatch deleting the temp file and failing the pack;
 * - only https on firefox-settings-attachments.cdn.mozilla.net, checked before connecting and on every redirect
 *   (redirects are followed by hand, at most three, each one checked), no cache;
 * - connect timeout 15 s, read timeout 30 s, no more bytes than the catalogue's size;
 * - install paths resolved under the base folder with a canonical path check;
 * - nothing is fetched while the Network switch is off (networkAllowed, read just before each connection).
 * The rest (staging, shared file owners, one commit per plan, progress reporting) is as is.
 */
package com.yaz.language.translate

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uniffi.translator_core.TaskInstall

private const val TAG = "PackInstaller"
private const val MIN_PROGRESS_STEP_BYTES = 128L * 1024
private const val STAGING_DIR = ".staging"

// Parallel downloads create shared staging parents at the same moment, and File.mkdirs on Android 6
// gives up when another thread wins that race, leaving the leaf uncreated.
private fun ensureDirectory(dir: File) {
    if (dir.isDirectory) return
    dir.parentFile?.let(::ensureDirectory)
    if (!dir.mkdir() && !dir.isDirectory) throw IOException("Failed to create directory $dir")
}

// A plan's files land here first and move into the install root together under the catalog's
// publish lock, so a rescan never sees a pack with only some of its files.
class StagedInstall(
    private val root: File,
    private val filePathManager: FilePathManager
) {
    private val files = ConcurrentLinkedQueue<String>()

    fun stagedFile(installPath: String): File = resolveUnder(root, installPath)

    fun keepFile(installPath: String) = files.add(installPath)

    fun moveIntoPlace() {
        files.forEach { path ->
            val target = filePathManager.resolveInstallPath(path)
            target.parentFile?.let(::ensureDirectory)
            // Same filesystem (the staging folder is inside the base folder): a rename, never a copy.
            Files.move(stagedFile(path).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun discard() {
        root.deleteRecursively()
    }
}

class PackInstaller(
    private val filePathManager: FilePathManager,
    private val catalogRepository: CatalogRepository,
    /** The Network switch (SettingsStore.networkAllowed), read just before each connection. */
    private val networkAllowed: () -> Boolean
) {
    // Plans can share files (every TTS voice depends on the espeak pack). Each file is fetched by
    // the first in-flight run that needs it; later runs wait for that run to finish and then find
    // it installed. Waits only ever point at earlier runs, so they can't form a cycle.
    private val fileOwners = mutableMapOf<String, Job>()

    init {
        // Staging left behind by a killed process is never committed.
        File(filePathManager.currentBaseDir(), STAGING_DIR).deleteRecursively()
    }

    // The whole plan commits at once: the catalog reads a language with one direction installed and
    // the other still missing files as needing repair, so packs can't land one by one.
    suspend fun installPlan(
        plan: DownloadPlan,
        onProgress: (Long) -> Unit
    ) {
        val done = Job()
        val earlierOwners =
            synchronized(fileOwners) {
                plan.tasks.associateWith { task ->
                    val owner = fileOwners[task.installPath]
                    if (owner == null) fileOwners[task.installPath] = done
                    owner
                }
            }
        val staging =
            StagedInstall(File(File(filePathManager.currentBaseDir(), STAGING_DIR), UUID.randomUUID().toString()), filePathManager)
        try {
            coroutineScope {
                earlierOwners
                    .map { (task, owner) ->
                        async {
                            // join rather than await: the owner failing or being cancelled must not end this run;
                            // install then fetches the file itself.
                            owner?.join()
                            install(task, staging, onProgress)
                        }
                    }.awaitAll()
            }
            catalogRepository.commitInstall(staging, plan.deleteAfter)
        } finally {
            synchronized(fileOwners) { fileOwners.values.removeAll { it === done } }
            done.complete()
            staging.discard()
        }
    }

    // Reports deltas through onProgress, adding up to exactly task.sizeBytes once the task lands.
    private suspend fun install(
        task: DownloadTask,
        staging: StagedInstall,
        onProgress: (Long) -> Unit
    ) {
        val expectedSize = task.sizeBytes.toLong()
        if (catalogRepository.catalog.value.downloadTaskInstalled(task)) {
            Log.i(TAG, "Reusing installed ${task.packId}:${task.installPath}")
            onProgress(expectedSize)
            return
        }
        // No zip, no gunzip: the catalogue's files are raw, the hash is of the bytes on the wire.
        if (task.install != TaskInstall.Raw) throw PackSecurityException("Refused pack: it needs an archive or decompression")
        val expected = expectedFor(catalogRepository.packFiles, task.installPath, task.url, expectedSize)
        val target = staging.stagedFile(task.installPath)
        val reported = download(expected, target, onProgress)
        staging.keepFile(task.installPath)
        onProgress(max(0L, expectedSize - reported))
        Log.i(TAG, "Staged ${task.packId}:${task.installPath}")
    }

    // Returns the bytes reported through onProgress, capped at the catalogue's size.
    private suspend fun download(
        expected: ExpectedFile,
        target: File,
        onProgress: (Long) -> Unit
    ): Long =
        withContext(Dispatchers.IO) {
            target.parentFile?.let(::ensureDirectory)
            if (!networkAllowed()) throw NetworkNotAllowedException()
            val progress = ProgressReporter(expected.sizeBytes, onProgress)
            val connection = openChecked(expected.url, expected.sizeBytes)
            try {
                connection.inputStream.use { raw ->
                    writeVerified(raw, target, expected, onBytes = progress::advance, checkActive = { ensureActive() })
                }
            } finally {
                connection.disconnect()
            }
            progress.flush()
            progress.reported
        }

    // Redirects followed by hand: HttpURLConnection would follow one to any host and even from https to http.
    // Each target is checked like the first address (checkRedirect), at most PackPolicy.MAX_REDIRECTS.
    private fun openChecked(
        url: String,
        maxBytes: Long
    ): HttpURLConnection {
        var uri = checkDownloadUrl(url)
        repeat(PackPolicy.MAX_REDIRECTS + 1) {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = PackPolicy.CONNECT_TIMEOUT_MS
                connection.readTimeout = PackPolicy.READ_TIMEOUT_MS
                connection.useCaches = false
                connection.requestMethod = "GET"
                // Raw bytes, so the size cap and the hash are of what the catalogue describes.
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                // The URL the connection really holds, parsed by java.net.URL, must name the same host.
                checkDownloadUrl(connection.url.toString())
                when (code) {
                    HttpURLConnection.HTTP_OK -> {
                        val length = connection.contentLengthLong
                        if (length > maxBytes) throw PackSecurityException("Refused file: larger than the catalogue says")
                        return connection
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308 -> uri = checkRedirect(uri, connection.getHeaderField("Location"))
                    else -> throw IOException("Download refused by the server (HTTP $code)")
                }
            } catch (e: Throwable) {
                connection.disconnect()
                throw e
            }
            connection.disconnect()
        }
        throw PackSecurityException("Refused download: too many redirects")
    }
}

private class ProgressReporter(
    private val expectedBytes: Long,
    private val onProgress: (Long) -> Unit
) {
    private val step = max(MIN_PROGRESS_STEP_BYTES, expectedBytes / 20)
    private var read = 0L
    var reported = 0L
        private set

    fun advance(bytes: Int) {
        read += bytes
        if (read - reported > step) flush()
    }

    fun flush() {
        val target = if (expectedBytes > 0) minOf(read, expectedBytes) else read
        if (target <= reported) return
        onProgress(target - reported)
        reported = target
    }
}
