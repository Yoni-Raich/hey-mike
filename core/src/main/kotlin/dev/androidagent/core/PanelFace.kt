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
 * What a saved panel shows right now, for a surface that draws it: the
 * floating card, the notification and the widget all read this, so the
 * three never disagree about a value or a switch.
 */
data class PanelFace(val id: String, val title: String, val surface: PanelSurface, val rows: List<Row>) {
    sealed interface Row {
        data class Note(val text: String, val tone: Tone) : Row
        /** A readout; [value] is the live value once read, the written one before. */
        data class Reading(val label: String, val value: String, val tone: Tone) : Row
        /** An action tile. [on] is set for a switch; [problem] is why its last tap failed. */
        data class Tile(
            val actionId: String, val label: String, val caption: String, val on: Boolean?,
            val running: Boolean, val problem: String?,
        ) : Row
        /** A live slider; [value] is where its thumb sits. */
        data class Track(
            val sliderId: String, val label: String, val min: Double, val max: Double, val step: Double,
            val value: Double, val valueText: String,
        ) : Row {
            val intervals: Int get() = ((max - min) / step).let { Math.round(it).toInt() }.coerceAtLeast(1)
            val progress: Int get() = Math.round((value - min) / step).toInt().coerceIn(0, intervals)
            fun valueAt(progress: Int): Double = (min + progress.coerceIn(0, intervals) * step).coerceIn(min, max)
        }
    }

    val readings: List<Row.Reading> get() = rows.filterIsInstance<Row.Reading>()
    val tiles: List<Row.Tile> get() = rows.filterIsInstance<Row.Tile>()

    /** The readings in one line, for a notification's text: "Song · 1:23". */
    val summary: String
        get() = readings.joinToString(" · ") { if (it.label.isBlank()) it.value else "${it.label}: ${it.value}" }

    companion object {
        /** The face of [saved] with its [live] state, or null when it is not active. */
        fun of(saved: SavedPanel, live: PanelLive?): PanelFace? {
            if (saved.status != SavedPanel.Status.ACTIVE) return null
            val panel = saved.panel ?: return null
            val state = live ?: PanelLive()
            val rows = panel.blocks.withIndex().mapNotNull { (index, block) ->
                when (block) {
                    is PanelBlock.Text -> Row.Note(block.text, block.tone)
                    is PanelBlock.Status -> Row.Reading(
                        block.label,
                        state.values[index]?.takeIf { it.isNotEmpty() } ?: if (block.bind != null && block.value.isEmpty()) "-" else block.value,
                        block.tone,
                    )
                    is PanelBlock.Action -> {
                        val tap = state.actions[block.id] ?: PanelActionState()
                        Row.Tile(
                            block.id, block.label, block.detail.ifBlank { block.call.caption },
                            on = if (block.switch) tap.on ?: block.on else null,
                            running = tap.running,
                            problem = tap.message.takeIf { tap.ok == false && it.isNotBlank() },
                        )
                    }
                    is PanelBlock.Slider -> if (!block.live) null else {
                        val tap = state.actions[block.id] ?: PanelActionState()
                        val value = tap.value?.toDoubleOrNull()?.let(block::snap) ?: block.start
                        Row.Track(block.id, block.label, block.min, block.max, block.step, value, block.format(value))
                    }
                    // Inputs and app controls never pass the checks for a panel that outlives the turn.
                    else -> null
                }
            }
            return PanelFace(saved.id, saved.title, panel.placement.surface, rows)
        }

        /** Every shown panel on [surface], oldest first. */
        fun on(surface: PanelSurface, panels: List<SavedPanel>, live: Map<String, PanelLive>): List<PanelFace> =
            panels.asSequence()
                .filter { !it.hidden && it.placement?.surface == surface }
                .mapNotNull { of(it, live[it.id]) }
                .toList()
    }
}
