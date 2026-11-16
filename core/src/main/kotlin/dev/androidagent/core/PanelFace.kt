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
data class PanelFace(
    val id: String, val title: String, val surface: PanelSurface, val rows: List<Row>,
    /** A floating card that keeps the screen on while it shows (cooking steps). */
    val keepScreenOn: Boolean = false,
) {
    sealed interface Row {
        data class Note(val text: String, val tone: Tone) : Row
        /** A readout; [value] is the live value once read, the written one before. */
        data class Reading(val label: String, val value: String, val tone: Tone) : Row
        /** An action tile. [on] is set for a switch; [problem] is why its last tap failed. */
        data class Tile(
            val actionId: String, val label: String, val caption: String, val on: Boolean?,
            val running: Boolean, val problem: String?,
        ) : Row
        /** A live slider; [value] is where its thumb sits, shown and sent with [decimals] places as in the chat. */
        data class Track(
            val sliderId: String, val label: String, val min: Double, val max: Double, val step: Double,
            val value: Double, val valueText: String, val decimals: Int = AgentPanels.trackDecimals(min, step),
        ) : Row {
            val intervals: Int get() = ((max - min) / step).let { Math.round(it).toInt() }.coerceAtLeast(1)
            val progress: Int get() = Math.round((value - min) / step).toInt().coerceIn(0, intervals)
            fun valueAt(progress: Int): Double =
                AgentPanels.round(min + progress.coerceIn(0, intervals) * step, decimals).coerceIn(min, max)
            /** The number at [progress] as the slider shows it while dragged. */
            fun textAt(progress: Int): String = AgentPanels.fixed(valueAt(progress), decimals)
        }
        /** A checklist. [canAdd] lets a surface offer a way to add an item. */
        data class Checks(val blockId: String, val label: String, val items: List<PanelItem>, val canAdd: Boolean) : Row {
            val left: Int get() = items.count { !it.checked }
        }
        /** A counter with its bounds; [goal] when it counts toward one. */
        data class Count(val blockId: String, val label: String, val count: Long, val min: Long, val max: Long, val goal: Long?) : Row
        /**
         * A timer. Running: [endsAtMs] is set and a surface counts down to it
         * itself, so nothing is redrawn each second. Otherwise [leftMs] is fixed.
         */
        data class Clock(val blockId: String, val label: String, val endsAtMs: Long?, val leftMs: Long, val done: Boolean) : Row {
            val running: Boolean get() = endsAtMs != null
        }
        /** A bar: [done] of [total]. */
        data class Bar(val label: String, val done: Double, val total: Double) : Row {
            val fraction: Float get() = if (total <= 0) 0f else (done / total).toFloat().coerceIn(0f, 1f)
        }
        /** Text the user writes on the card. */
        data class Memo(val blockId: String, val label: String, val text: String, val placeholder: String) : Row
    }

    val readings: List<Row.Reading> get() = rows.filterIsInstance<Row.Reading>()
    val tiles: List<Row.Tile> get() = rows.filterIsInstance<Row.Tile>()
    val checks: Row.Checks? get() = rows.filterIsInstance<Row.Checks>().firstOrNull()

    /** The readings in one line, for a notification's text: "Song · 1:23". */
    val summary: String
        get() = rows.mapNotNull { row ->
            when (row) {
                is Row.Reading -> if (row.label.isBlank()) row.value else "${row.label}: ${row.value}"
                is Row.Checks -> named(row.label, "${row.left} left")
                is Row.Count -> named(row.label, row.goal?.let { "${row.count} of $it" } ?: row.count.toString())
                is Row.Clock -> named(row.label, when {
                    row.done -> "done"
                    // A running time goes stale in a line of text; the surface shows it counting.
                    row.running -> "running"
                    else -> PanelBind.clock(row.leftMs)
                })
                else -> null
            }
        }.joinToString(" · ")

    private fun named(label: String, value: String) = if (label.isBlank()) value else "$label: $value"

    companion object {
        /**
         * The face of [saved] with its [live] values and its own [own] state
         * at [now], or null when it is not active.
         */
        fun of(saved: SavedPanel, live: PanelLive?, own: PanelState? = null, now: Long = System.currentTimeMillis()): PanelFace? {
            if (saved.status != SavedPanel.Status.ACTIVE) return null
            val panel = saved.panel ?: return null
            val state = live ?: PanelLive()
            val mine = own ?: PanelStates.initial(panel, now)
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
                        val on = if (block.switch) tap.on ?: block.on else null
                        // Plain words for what the next tap does ("Turn the flashlight off"), never a raw call.
                        val next = block.callFor(on != true)?.let(PanelCallWords::describe)
                            ?: (block.effect as? PanelEffect.Local)?.let { PanelOpWords.describe(panel, it.op) }
                            ?: (block.effect as? PanelEffect.Steps)?.let { PanelOpWords.steps(panel, it) }
                        Row.Tile(
                            block.id, block.label, block.detail.ifBlank { next ?: block.caption },
                            on = on,
                            running = tap.running,
                            problem = tap.message.takeIf { tap.ok == false && it.isNotBlank() },
                        )
                    }
                    is PanelBlock.Slider -> if (!block.live) null else {
                        val tap = state.actions[block.id] ?: PanelActionState()
                        val value = tap.value?.toDoubleOrNull()?.let(block::snap) ?: block.start
                        Row.Track(block.id, block.label, block.min, block.max, block.step, value, block.format(value), block.decimals)
                    }
                    is PanelBlock.Checklist -> Row.Checks(
                        block.id, block.label, (mine.blocks[block.id] as? BlockState.Items)?.items ?: block.items, block.canAdd,
                    )
                    is PanelBlock.Counter -> Row.Count(
                        block.id, block.label, (mine.blocks[block.id] as? BlockState.Count)?.count ?: block.start,
                        block.min, block.max, block.goal,
                    )
                    is PanelBlock.Timer -> {
                        val clock = mine.blocks[block.id] as? BlockState.Clock ?: BlockState.Clock(block.seconds * 1_000)
                        Row.Clock(block.id, block.label, clock.endsAtMs, clock.leftAt(now), done = clock.doneAtMs != null)
                    }
                    is PanelBlock.Note -> Row.Memo(
                        block.id, block.label, (mine.blocks[block.id] as? BlockState.Memo)?.text ?: block.text, block.placeholder,
                    )
                    is PanelBlock.Progress -> PanelStates.progress(panel, mine, block)?.let { (done, total) -> Row.Bar(block.label, done, total) }
                    // Inputs and app controls never pass the checks for a panel that outlives the turn.
                    else -> null
                }
            }
            return PanelFace(saved.id, saved.title, panel.placement.surface, rows, panel.placement.keepScreenOn)
        }

        /** Every shown panel on [surface], oldest first. */
        fun on(
            surface: PanelSurface, panels: List<SavedPanel>, live: Map<String, PanelLive>,
            states: Map<String, PanelState> = emptyMap(), now: Long = System.currentTimeMillis(),
        ): List<PanelFace> =
            panels.asSequence()
                .filter { !it.hidden && it.placement?.surface == surface }
                .mapNotNull { of(it, live[it.id], states[it.id], now) }
                .toList()
    }
}
