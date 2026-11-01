/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/CatalogRepository.kt
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
 * Changed: the catalogue is the bundled asset only, never read from disk or fetched (OWASP MASVS-NETWORK-1:
 * nothing but model files comes over the network), so reloadIndex, the stale index_v*.json and kokoro clean-up
 * and the cached-index read are cut, and open() passes no disk catalogue. discardMigration is cut (ONNX to MNN).
 * Added: packFiles, the bundled catalogue's SHA-256 and size per file, which the installer checks against
 * (the engine's DownloadTask carries neither).
 */
package com.yaz.language.translate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.bindings.CatalogHandle

private const val BUNDLED_INDEX_ASSET = "index_v7.json"

// The one session per process. Every install-state change goes through here so the published
// catalog always follows it; the session's warm engines live as long as the process does.
class CatalogRepository private constructor(
    private val handle: CatalogHandle,
    /** The bundled catalogue's files by installPath: what each downloaded file must hash to. */
    val packFiles: Map<String, ExpectedFile>
) {
    // Serializes change+publish so a slower concurrent refresh can't publish rows older than
    // the ones already out.
    private val lock = Any()
    private val published = MutableStateFlow(LanguageCatalog.of(handle))
    val catalog: StateFlow<LanguageCatalog> = published.asStateFlow()

    companion object {
        fun open(
            context: Context,
            filePathManager: FilePathManager
        ): CatalogRepository {
            val bundledJson = context.assets.open(BUNDLED_INDEX_ASSET).bufferedReader().use { it.readText() }
            val handle = CatalogHandle.open(bundledJson, null, filePathManager.currentBaseDir().absolutePath)
            return CatalogRepository(handle, parseCatalogueFiles(bundledJson))
        }
    }

    fun refresh() = publishAfter { handle.refresh() }

    fun applyDeletePlan(plan: DeletePlan) = publishAfter { handle.applyDeletePlan(plan) }

    fun commitInstall(
        staging: StagedInstall,
        deleteAfter: DeletePlan
    ) = publishAfter {
        staging.moveIntoPlace()
        handle.applyDeletePlan(deleteAfter)
    }

    private inline fun publishAfter(change: () -> Unit) {
        synchronized(lock) {
            change()
            published.value = LanguageCatalog.of(handle)
        }
    }
}
