/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import java.io.File

/** Usage limits an engine reported, and when. */
data class UsageReading(val limits: List<UsageLimit>, val readAtMillis: Long)

/**
 * The last usage limits one engine reported, kept on disk so they show after
 * an app restart. Claude reports its limits only while a process runs, so
 * without this the usage sheet starts empty every time.
 *
 * A window whose reset time has passed is dropped on the way out: what it
 * said is no longer true, and the real value is unknown until the engine
 * reports again. The file holds percentages and reset times only.
 */
class LastUsageStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val book = AccountUsageBook(file, clock)

    /** Keep [limits] as the latest reading. An empty list keeps the last one. */
    fun save(limits: List<UsageLimit>) = book.record(KEY, limits, listOf(KEY))

    /** The latest reading without its expired windows, or null when nothing current is left. */
    fun read(nowMillis: Long = clock()): UsageReading? {
        val saved = book.all()[KEY] ?: return null
        val limits = current(saved.limits, nowMillis / 1000L)
        return if (limits.isEmpty()) null else UsageReading(limits, saved.readAtMillis)
    }

    /** Forget the reading, for a sign-out: it belongs to that account. */
    fun clear() {
        file.delete()
    }

    companion object {
        private const val KEY = "last"

        /** [limits] without the windows whose reset time is at or before [nowSeconds]. */
        fun current(limits: List<UsageLimit>, nowSeconds: Long): List<UsageLimit> =
            limits.filter { limit -> limit.resetsAt.let { it == null || it <= 0L || it > nowSeconds } }
    }
}
