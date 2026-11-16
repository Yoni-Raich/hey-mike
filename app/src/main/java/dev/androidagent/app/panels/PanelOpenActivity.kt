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

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import dev.androidagent.app.AgentApplication
import dev.androidagent.core.PanelOp
import dev.androidagent.core.PanelOpen

/** The intent for each allowed [PanelOpen] shape. Nothing else can be built here. */
internal object PanelOpenIntents {
    fun of(context: Context, target: PanelOpen): Intent? = when (target) {
        is PanelOpen.App -> context.packageManager.getLaunchIntentForPackage(target.pkg)
        is PanelOpen.Place -> Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))
        is PanelOpen.Directions -> Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        // The dialer, never a call: ACTION_DIAL waits for the user to press call.
        is PanelOpen.Dial -> Intent(Intent.ACTION_DIAL, Uri.parse(target.uri))
        is PanelOpen.Url -> Intent(Intent.ACTION_VIEW, Uri.parse(target.url)).addCategory(Intent.CATEGORY_BROWSABLE)
        // The camera app itself; the user takes the photo, and nothing comes back to the panel.
        PanelOpen.Camera -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Starts an `open` button's target from the user's tap on a notification,
 * widget or floating card. Not exported: only this app's own intents reach
 * it. The target is read from the approved panel and checked against the
 * allowlist again here, never taken from the intent.
 */
class PanelOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = (application as? AgentApplication)?.graph?.panels
        val panelId = intent.getStringExtra(EXTRA_PANEL)
        val actionId = intent.getStringExtra(EXTRA_ACTION)
        if (host != null && panelId != null && actionId != null) {
            host.openTarget(panelId, actionId).fold(
                onSuccess = { target ->
                    val open = PanelOpenIntents.of(this, target)
                    try {
                        if (open == null) throw ActivityNotFoundException()
                        startActivity(open)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, "No app on this phone can do that: ${target.words}.", Toast.LENGTH_LONG).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(this, "Android did not allow that: ${target.words}.", Toast.LENGTH_LONG).show()
                    }
                },
                onFailure = { Toast.makeText(this, it.message ?: "That cannot be opened.", Toast.LENGTH_LONG).show() },
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_PANEL = "panel"
        const val EXTRA_ACTION = "action"

        fun intent(context: Context, panelId: String, actionId: String): Intent =
            Intent(context, PanelOpenActivity::class.java)
                .setData(Uri.parse("heymike-panel://open/${Uri.encode(panelId)}/${Uri.encode(actionId)}"))
                .putExtra(EXTRA_PANEL, panelId)
                .putExtra(EXTRA_ACTION, actionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pending(context: Context, panelId: String, actionId: String): PendingIntent = PendingIntent.getActivity(
            context, "open:$panelId:$actionId".hashCode(), intent(context, panelId, actionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * A small box to add an item to a panel's list, for a widget (a widget cannot
 * take text). Not exported. The text goes through the same `add` op, rules
 * and bounds as every other edit, to that one panel only.
 */
class PanelAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = (application as? AgentApplication)?.graph?.panels
        val panelId = intent.getStringExtra(EXTRA_PANEL)
        val blockId = intent.getStringExtra(EXTRA_BLOCK)
        if (host == null || panelId == null || blockId == null || host.active(panelId) == null) {
            finish()
            return
        }
        val field = EditText(this).apply {
            hint = "Item"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(dev.androidagent.core.PanelStates.MAX_ITEM_TEXT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(host.active(panelId)?.title?.ifBlank { null } ?: "Add an item")
            .setView(field)
            .setPositiveButton("Add") { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) {
                    host.edit(panelId, PanelOp("add", blockId, text = text))?.let {
                        Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    companion object {
        const val EXTRA_PANEL = "panel"
        const val EXTRA_BLOCK = "block"

        fun pending(context: Context, panelId: String, blockId: String): PendingIntent = PendingIntent.getActivity(
            context, "add:$panelId:$blockId".hashCode(),
            Intent(context, PanelAddActivity::class.java)
                .setData(Uri.parse("heymike-panel://add/${Uri.encode(panelId)}/${Uri.encode(blockId)}"))
                .putExtra(EXTRA_PANEL, panelId)
                .putExtra(EXTRA_BLOCK, blockId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
