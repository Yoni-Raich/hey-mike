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

package dev.androidagent.app.panels

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.R
import dev.androidagent.core.PanelEffect
import dev.androidagent.core.PanelFace
import dev.androidagent.core.PanelHost
import dev.androidagent.core.PanelLive
import dev.androidagent.core.PanelOp
import dev.androidagent.core.PanelState
import dev.androidagent.core.PanelSurface
import dev.androidagent.core.SavedPanel
import java.text.DateFormat
import java.util.Date

/**
 * A home-screen widget that draws one saved widget panel. Built with
 * RemoteViews, as the usage widget is: a widget can show text and take taps,
 * nothing else, so a panel for it holds only statuses, notes and actions
 * (the panel checks refuse inputs and sliders there).
 */
class SavedPanelWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        val host = graph.panels
        SavedPanelWidget.render(context.applicationContext, host, host.panels.value, host.live.value, host.states.value)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        SavedPanelWidget.forget(context.applicationContext, ids)
    }
}

object SavedPanelWidget {
    private const val PREFS = "saved_panel_widgets"
    private const val TILES_PER_ROW = 3
    /** Below Android 12 a widget cannot scroll a list it builds itself, so it shows this many items. */
    private const val FIXED_ITEMS = 6

    /** Whether the launcher can place a widget for the app when asked. */
    fun canPin(context: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true }.getOrDefault(false)

    /**
     * Asks the launcher to place a widget for [panelId]. The launcher shows its
     * own confirmation; once placed, [SavedPanelActionReceiver] ties the new
     * widget to the panel. Returns false when the launcher cannot do it.
     */
    fun requestPin(context: Context, host: PanelHost, panelId: String): Boolean {
        val saved = host.active(panelId) ?: return false
        if (saved.placement?.surface != PanelSurface.WIDGET) return false
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return runCatching {
            manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(provider(context), null, SavedPanelActionReceiver.pinned(context, panelId))
        }.getOrDefault(false)
    }

    /** Ties placed widget [widgetId] to [panelId], if both are what they claim to be. */
    fun bind(context: Context, host: PanelHost, widgetId: Int, panelId: String) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        // Only a widget of this provider, and only an approved widget panel.
        if (manager.getAppWidgetInfo(widgetId)?.provider != provider(context)) return
        val saved = host.active(panelId) ?: return
        if (saved.placement?.surface != PanelSurface.WIDGET) return
        prefs(context).edit().putString(widgetId.toString(), panelId).apply()
        render(context, host, host.panels.value, host.live.value, host.states.value)
    }

    fun forget(context: Context, ids: IntArray) {
        prefs(context).edit().apply { ids.forEach { remove(it.toString()) } }.apply()
    }

    /** The number of placed widgets that draw [panelId]. */
    fun placed(context: Context, panelId: String): Int {
        val manager = AppWidgetManager.getInstance(context) ?: return 0
        val ids = runCatching { manager.getAppWidgetIds(provider(context)) }.getOrDefault(IntArray(0)).toSet()
        return prefs(context).all.count { (key, value) -> value == panelId && key.toIntOrNull() in ids }
    }

    /**
     * Redraws every placed widget. A widget whose panel was removed says so:
     * the app cannot take a widget off the home screen, only the user can.
     * Returns the ids of the panels that are drawn on a widget now.
     */
    fun render(
        context: Context, host: PanelHost, panels: List<SavedPanel>, live: Map<String, PanelLive>,
        states: Map<String, PanelState> = emptyMap(),
    ): Set<String> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptySet()
        val ids = runCatching { manager.getAppWidgetIds(provider(context)) }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return emptySet()
        val map = prefs(context)
        val drawn = mutableSetOf<String>()
        ids.forEach { widgetId ->
            val panelId = map.getString(widgetId.toString(), null)
            val saved = panelId?.let(host::active)?.takeIf { it.placement?.surface == PanelSurface.WIDGET }
            val face = saved?.takeIf { !it.hidden }?.let { PanelFace.of(it, live[it.id], states[it.id]) }
            val views = when {
                face != null -> {
                    drawn += face.id
                    panel(context, host, face)
                }
                panelId == null -> message(context, context.getString(R.string.panel_widget_unbound))
                saved != null -> message(context, context.getString(R.string.panel_widget_hidden))
                else -> message(context, context.getString(R.string.panel_widget_removed))
            }
            runCatching { manager.updateAppWidget(widgetId, views) }
        }
        // Panels that are gone keep their ids here only until the widget is taken off.
        return drawn
    }

    private fun panel(context: Context, host: PanelHost, face: PanelFace): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_panel)
        views.setTextViewText(R.id.widget_panel_title, face.title.ifBlank { "Mike" })
        views.setOnClickPendingIntent(R.id.widget_panel_title, open(context, face.id))
        val clockTime = DateFormat.getTimeInstance(DateFormat.SHORT)
        val notes = face.rows.mapNotNull { row ->
            when (row) {
                is PanelFace.Row.Note -> row.text
                is PanelFace.Row.Reading -> if (row.label.isBlank()) row.value else "${row.label}: ${row.value}"
                is PanelFace.Row.Count -> listOf(row.label, row.count.toString() + (row.goal?.let { " of $it" } ?: ""))
                    .filter(String::isNotBlank).joinToString(": ")
                // A widget is redrawn seldom, so a running timer shows when it ends, not a count.
                is PanelFace.Row.Clock -> listOf(
                    row.label,
                    when {
                        row.done -> "time is up"
                        row.endsAtMs != null -> "ends at " + clockTime.format(Date(row.endsAtMs!!))
                        else -> minutes(row.leftMs) + " left"
                    },
                ).filter(String::isNotBlank).joinToString(": ")
                is PanelFace.Row.Bar -> listOf(row.label, "${trim(row.done)} of ${trim(row.total)}").filter(String::isNotBlank).joinToString(": ")
                is PanelFace.Row.Memo -> row.text.takeIf(String::isNotBlank)?.let { if (row.label.isBlank()) it else "${row.label}: $it" }
                else -> null
            }
        }
        views.setTextViewText(R.id.widget_panel_text, notes.joinToString("\n"))
        views.setViewVisibility(R.id.widget_panel_text, if (notes.isEmpty()) View.GONE else View.VISIBLE)
        // A live slider has no widget form; the checks keep it off widgets, and this says so if one slips by.
        val unsupported = face.rows.any { it is PanelFace.Row.Track }
        views.setTextViewText(R.id.widget_panel_message, if (unsupported) context.getString(R.string.panel_widget_unsupported) else "")
        views.setViewVisibility(R.id.widget_panel_message, if (unsupported) View.VISIBLE else View.GONE)
        views.removeAllViews(R.id.widget_panel_tiles)
        val checks = face.checks
        if (checks != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list(context, views, face.id, checks)
        } else {
            views.setViewVisibility(R.id.widget_panel_list, View.GONE)
            checks?.items?.sortedBy { it.checked }?.take(FIXED_ITEMS)?.forEach { item ->
                views.addView(R.id.widget_panel_tiles, item(context, face.id, checks.blockId, item, fill = false))
            }
        }
        // The panel's buttons, then what its list, counter or timer offers.
        val extra = face.rows.mapNotNull { row ->
            when (row) {
                is PanelFace.Row.Checks -> if (row.canAdd) Extra("Add", PanelAddActivity.pending(context, face.id, row.blockId)) else null
                is PanelFace.Row.Count -> if (row.count < row.max) Extra("+1", SavedPanelActionReceiver.op(context, face.id, PanelOp("inc", row.blockId)), row.label) else null
                is PanelFace.Row.Clock -> Extra(
                    if (row.running) "Pause" else if (row.done) "Again" else "Start",
                    SavedPanelActionReceiver.op(context, face.id, PanelOp(if (row.running) "pause" else if (row.done) "reset" else "start", row.blockId)),
                    row.label,
                )
                else -> null
            }
        }
        val tiles = face.tiles.map { tile -> tile(context, host, face.id, tile) } + extra.map { extra(context, it) }
        tiles.chunked(TILES_PER_ROW).forEach { row ->
            val line = RemoteViews(context.packageName, R.layout.widget_panel_row)
            row.forEach { line.addView(R.id.widget_panel_row, it) }
            views.addView(R.id.widget_panel_tiles, line)
        }
        val fixedItems = checks != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && checks.items.isNotEmpty()
        views.setViewVisibility(R.id.widget_panel_tiles, if (tiles.isEmpty() && !fixedItems) View.GONE else View.VISIBLE)
        return views
    }

    private data class Extra(val label: String, val intent: PendingIntent, val state: String = "")

    private fun extra(context: Context, extra: Extra): RemoteViews {
        val item = RemoteViews(context.packageName, R.layout.widget_panel_tile)
        item.setTextViewText(R.id.widget_tile_label, extra.label)
        item.setTextViewText(R.id.widget_tile_state, extra.state)
        item.setViewVisibility(R.id.widget_tile_state, if (extra.state.isBlank()) View.GONE else View.VISIBLE)
        item.setContentDescription(R.id.widget_tile_root, listOf(extra.label, extra.state).filter(String::isNotBlank).joinToString(", "))
        item.setOnClickPendingIntent(R.id.widget_tile_root, extra.intent)
        return item
    }

    /**
     * Android 12+: the list as a scrolling collection built here, no service.
     * Each row fills in only which item to toggle; the panel and the list are
     * in the template, and the receiver allows only what a surface may do.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun list(context: Context, views: RemoteViews, panelId: String, checks: PanelFace.Row.Checks) {
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        checks.items.sortedBy { it.checked }.forEach { item ->
            items.addItem(item.id.hashCode().toLong(), item(context, panelId, checks.blockId, item, fill = true))
        }
        views.setRemoteAdapter(R.id.widget_panel_list, items.build())
        views.setPendingIntentTemplate(R.id.widget_panel_list, SavedPanelActionReceiver.toggleTemplate(context, panelId, checks.blockId))
        views.setViewVisibility(R.id.widget_panel_list, if (checks.items.isEmpty()) View.GONE else View.VISIBLE)
    }

    private fun item(context: Context, panelId: String, blockId: String, item: dev.androidagent.core.PanelItem, fill: Boolean): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_panel_item)
        val text = (item.qty?.let { "$it × " } ?: "") + item.text
        row.setTextViewText(R.id.widget_item_mark, if (item.checked) "☑" else "☐")
        row.setTextViewText(R.id.widget_item_text, text)
        row.setInt(R.id.widget_item_text, "setPaintFlags", if (item.checked) android.graphics.Paint.STRIKE_THRU_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG else android.graphics.Paint.ANTI_ALIAS_FLAG)
        row.setContentDescription(R.id.widget_item_root, "$text, " + if (item.checked) "done" else "not done")
        if (fill) {
            row.setOnClickFillInIntent(R.id.widget_item_root, Intent().putExtra(SavedPanelActionReceiver.EXTRA_ITEM, item.id))
        } else {
            row.setOnClickPendingIntent(R.id.widget_item_root, SavedPanelActionReceiver.op(context, panelId, PanelOp("toggle", blockId, item = item.id)))
        }
        return row
    }

    private fun minutes(ms: Long): String {
        val total = (ms + 999) / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun trim(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else "%.1f".format(value)

    private fun tile(context: Context, host: PanelHost, panelId: String, tile: PanelFace.Row.Tile): RemoteViews {
        val item = RemoteViews(context.packageName, R.layout.widget_panel_tile)
        item.setTextViewText(R.id.widget_tile_label, tile.label)
        val state: String = when {
            tile.running -> "…"
            tile.problem != null -> tile.problem.orEmpty()
            tile.on == true -> "On"
            tile.on == false -> "Off"
            else -> tile.caption
        }
        item.setTextViewText(R.id.widget_tile_state, state)
        item.setViewVisibility(R.id.widget_tile_state, if (state.isBlank()) View.GONE else View.VISIBLE)
        item.setInt(R.id.widget_tile_root, "setBackgroundResource", if (tile.on == true) R.drawable.widget_panel_tile_on else R.drawable.widget_panel_tile)
        item.setContentDescription(R.id.widget_tile_root, listOf(tile.label, state).filter(String::isNotBlank).joinToString(", "))
        val opens = host.active(panelId)?.panel?.actions?.firstOrNull { it.id == tile.actionId }?.effect is PanelEffect.Open
        item.setOnClickPendingIntent(
            R.id.widget_tile_root,
            if (opens) PanelOpenActivity.pending(context, panelId, tile.actionId) else SavedPanelActionReceiver.tap(context, panelId, tile.actionId),
        )
        return item
    }

    private fun message(context: Context, text: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_panel)
        views.setTextViewText(R.id.widget_panel_title, context.getString(R.string.panel_widget_name))
        views.setTextViewText(R.id.widget_panel_text, text)
        views.setViewVisibility(R.id.widget_panel_text, View.VISIBLE)
        views.setViewVisibility(R.id.widget_panel_message, View.GONE)
        views.removeAllViews(R.id.widget_panel_tiles)
        views.setViewVisibility(R.id.widget_panel_tiles, View.GONE)
        views.setOnClickPendingIntent(R.id.widget_panel_root, open(context, null))
        return views
    }

    private fun open(context: Context, panelId: String?): PendingIntent = PendingIntent.getActivity(
        context, ("widget-open:" + panelId.orEmpty()).hashCode(),
        SavedPanelsActivity.intent(context, panelId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun provider(context: Context) = ComponentName(context, SavedPanelWidgetProvider::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
