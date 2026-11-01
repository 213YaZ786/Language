/*
 * Ported as is from DavidVentura/offline-translator v0.8.7 (commit e7716c6859e3d90b8883c667c1a3cfeef407df08), GPL-3.0:
 * https://github.com/DavidVentura/offline-translator/blob/e7716c6859e3d90b8883c667c1a3cfeef407df08/app/src/main/java/dev/davidv/translator/InputSplit.kt
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
 */
package com.yaz.language.translate

@JvmInline
value class InputSplit(
    val fraction: Float
) {
    init {
        require(fraction in MIN..MAX) { "input split $fraction out of range" }
    }

    fun dragged(deltaFraction: Float): InputSplit = InputSplit((fraction + deltaFraction).coerceIn(MIN, MAX))

    companion object {
        const val MIN = 0.25f
        const val MAX = 0.75f
        val DEFAULT = InputSplit(0.5f)

        fun parse(fraction: Float): InputSplit? = if (fraction in MIN..MAX) InputSplit(fraction) else null
    }
}
