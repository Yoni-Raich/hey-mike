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

/**
 * One line at the end of a run saying where its time went.
 *
 * A run that felt slow is the only evidence anyone has, and "it took a while"
 * cannot be acted on. Three buckets can: thinking is turns of the model, the
 * phone is device calls, and waiting is a person who had not answered yet. They
 * lead to different fixes - fewer turns, a faster path on the screen, or nothing
 * at all, because a user taking 20 seconds to tap Allow is not a performance
 * problem.
 *
 * Written into the chat as a system line rather than logged, so it is in front
 * of the person who just watched the run rather than in a log nobody reads.
 * Code that wants the numbers takes `AgentCoordinator.metrics`; the agent itself
 * cannot read this line, because messages live in the session database and not
 * in the workspace it can see.
 */
object RunSummary {

    /**
     * The summary, or null when there is nothing worth saying.
     *
     * A run that called no tool has one bucket and no breakdown, and a line
     * under every short answer is noise that teaches the user to skip it.
     */
    fun line(metrics: RunMetrics): String? {
        if (metrics.toolCalls <= 0) return null
        val total = metrics.totalMs
        if (total <= 0L) return null
        val parts = buildList {
            add("${duration(metrics.thinkingMs)} thinking")
            add(
                "${duration(metrics.toolMs)} on the phone across " +
                    "${metrics.toolCalls} ${if (metrics.toolCalls == 1) "call" else "calls"}",
            )
            // Only when someone was actually asked. Naming a bucket that is
            // empty invites reading it as a delay that happened.
            if (metrics.approvalMs > 0) add("${duration(metrics.approvalMs)} waiting for you")
        }
        val first = metrics.firstResponseMs?.let { " First reply after ${duration(it)}." }.orEmpty()
        return "Run summary: ${duration(total)} total - " + parts.joinToString(", ") + "." + first
    }

    /**
     * A duration as a person would say it.
     *
     * Tenths below ten seconds, because that is where the difference between
     * 1.2s and 8s matters; whole seconds above, because nobody acts on the
     * tenths of a minute-long run.
     */
    fun duration(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return when {
            safe < 1_000L -> "${safe}ms"
            safe < 10_000L -> String.format(java.util.Locale.US, "%.1fs", safe / 1_000.0)
            safe < 60_000L -> "${(safe + 500L) / 1_000L}s"
            else -> {
                val seconds = (safe + 500L) / 1_000L
                val minutes = seconds / 60L
                val rest = seconds % 60L
                if (rest == 0L) "${minutes}m" else "${minutes}m ${rest}s"
            }
        }
    }
}
