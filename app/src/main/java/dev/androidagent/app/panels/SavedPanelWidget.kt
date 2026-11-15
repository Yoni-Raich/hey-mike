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
import android.view.View
import android.widget.RemoteViews
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.R
import dev.androidagent.core.PanelFace
import dev.androidagent.core.PanelHost
import dev.androidagent.core.PanelLive
import dev.androidagent.core.PanelSurface
import dev.androidagent.core.SavedPanel

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
        SavedPanelWidget.render(context.applicationContext, host, host.panels.value, host.live.value)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        SavedPanelWidget.forget(context.applicationContext, ids)
    }
}

object SavedPanelWidget {
    private const val PREFS = "saved_panel_widgets"
    private const val TILES_PER_ROW = 3

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
        render(context, host, host.panels.value, host.live.value)
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
    fun render(context: Context, host: PanelHost, panels: List<SavedPanel>, live: Map<String, PanelLive>): Set<String> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptySet()
        val ids = runCatching { manager.getAppWidgetIds(provider(context)) }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return emptySet()
        val map = prefs(context)
        val drawn = mutableSetOf<String>()
        ids.forEach { widgetId ->
            val panelId = map.getString(widgetId.toString(), null)
            val saved = panelId?.let(host::active)?.takeIf { it.placement?.surface == PanelSurface.WIDGET }
            val face = saved?.takeIf { !it.hidden }?.let { PanelFace.of(it, live[it.id]) }
            val views = when {
                face != null -> {
                    drawn += face.id
                    panel(context, face)
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

    private fun panel(context: Context, face: PanelFace): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_panel)
        views.setTextViewText(R.id.widget_panel_title, face.title.ifBlank { "Mike" })
        views.setOnClickPendingIntent(R.id.widget_panel_title, open(context, face.id))
        val notes = face.rows.mapNotNull { row ->
            when (row) {
                is PanelFace.Row.Note -> row.text
                is PanelFace.Row.Reading -> if (row.label.isBlank()) row.value else "${row.label}: ${row.value}"
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
        face.tiles.chunked(TILES_PER_ROW).forEach { row ->
            val line = RemoteViews(context.packageName, R.layout.widget_panel_row)
            row.forEach { tile -> line.addView(R.id.widget_panel_row, tile(context, face.id, tile)) }
            views.addView(R.id.widget_panel_tiles, line)
        }
        views.setViewVisibility(R.id.widget_panel_tiles, if (face.tiles.isEmpty()) View.GONE else View.VISIBLE)
        return views
    }

    private fun tile(context: Context, panelId: String, tile: PanelFace.Row.Tile): RemoteViews {
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
        item.setOnClickPendingIntent(R.id.widget_tile_root, SavedPanelActionReceiver.tap(context, panelId, tile.actionId))
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
