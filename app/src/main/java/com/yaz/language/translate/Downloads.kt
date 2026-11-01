/*
 * Ported from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/Downloads.kt
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
 * Cut: SupportKind (adblock), and the ids and requests for dictionaries, text to speech, OCR upgrades,
 * translation upgrades (the catalogue is bundled, there is no newer one to move to), support files and the
 * catalogue download. Kept: a language's translation pack (Core), Repair, DownloadStatus.
 */
package com.yaz.language.translate

sealed interface DownloadId {
    data class Core(
        val language: Language
    ) : DownloadId

    data object Repair : DownloadId
}

sealed interface DownloadRequest {
    val id: DownloadId

    data class Core(
        val language: Language
    ) : DownloadRequest {
        override val id: DownloadId get() = DownloadId.Core(language)
    }

    data object Repair : DownloadRequest {
        override val id: DownloadId get() = DownloadId.Repair
    }
}

// A finished download has no status: the catalog is what says it is installed.
sealed interface DownloadStatus {
    data object Queued : DownloadStatus

    data class Running(
        val done: Long,
        val total: Long
    ) : DownloadStatus {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data class Failed(
        val reason: String
    ) : DownloadStatus
}

val DownloadStatus?.inFlightProgress: Float?
    get() =
        when (this) {
            DownloadStatus.Queued -> 0f
            is DownloadStatus.Running -> fraction
            is DownloadStatus.Failed, null -> null
        }
