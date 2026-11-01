/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/FilePathManager.kt
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
 * Changed: the base folder is always filesDir/translate (app-private, no user-chosen external folder, no
 * settings flow). resolveInstallPath refuses a path that leaves it (resolveUnder, OWASP MASVS-STORAGE-2).
 * Cut: the documents, dictionaries, adblock, mucab and catalog-file paths and the install markers (they belong
 * to features, a downloaded catalogue and zip packs that Language does not have).
 */
package com.yaz.language.translate

import android.content.Context
import java.io.File

class FilePathManager(
    val context: Context
) {
    private val baseDir: File = File(context.filesDir, "translate").also { it.mkdirs() }

    fun currentBaseDir(): File = baseDir

    fun getDataDir(): File = File(baseDir, "bin")

    /** [relativePath] comes from the catalogue: it is resolved under the base folder or refused. */
    fun resolveInstallPath(relativePath: String): File = resolveUnder(baseDir, relativePath)
}
