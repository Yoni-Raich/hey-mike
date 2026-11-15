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

package dev.androidagent.app.entry

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import dev.androidagent.app.MainActivity

/**
 * "Share to Mike" from any app. It runs in the sender's task, so it only
 * forwards the text and files to the chat screen in Mike's own task and
 * closes. The files travel as ClipData so the read grant moves with them.
 */
class ShareEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
        finish()
    }

    private fun forward(source: Intent?) {
        val share = SharedContent.from(source) ?: return
        val target = Intent(this, MainActivity::class.java)
            .setAction(EntryActions.ACTION_SHARE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        share.text?.let { target.putExtra(EntryActions.EXTRA_SHARE_TEXT, it) }
        if (share.uris.isNotEmpty()) {
            target.putParcelableArrayListExtra(EntryActions.EXTRA_SHARE_URIS, ArrayList(share.uris))
            val clip = ClipData.newRawUri("shared", share.uris.first())
            share.uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            target.clipData = clip
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(target)
    }
}

/** What a share intent carries, read the same way for one item or many. */
internal data class SharedContent(val text: String?, val uris: List<Uri>) {
    companion object {
        fun from(intent: Intent?): SharedContent? {
            intent ?: return null
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> return null
            }.filter { it.scheme == "content" }
            val text = joinText(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            )
            if (text == null && uris.isEmpty()) return null
            return SharedContent(text, uris)
        }

        /** A subject the text already starts with, as a shared link often does, is not repeated. */
        fun joinText(subject: String?, text: String?): String? {
            val s = subject?.trim().orEmpty()
            val t = text?.trim().orEmpty()
            return when {
                s.isEmpty() && t.isEmpty() -> null
                s.isEmpty() || t.startsWith(s) -> t
                t.isEmpty() -> s
                else -> "$s\n$t"
            }
        }
    }
}

/** Intents that open the chat screen for a share, a new chat or voice. */
object EntryActions {
    const val ACTION_SHARE = "dev.androidagent.app.action.SHARE"
    const val ACTION_NEW_CHAT = "dev.androidagent.app.action.NEW_CHAT"
    const val EXTRA_SHARE_TEXT = "dev.androidagent.app.extra.SHARE_TEXT"
    const val EXTRA_SHARE_URIS = "dev.androidagent.app.extra.SHARE_URIS"
}
