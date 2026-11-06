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

package dev.androidagent.app.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.androidagent.app.ui.MarkdownMessage
import dev.androidagent.core.widget.MessagePart
import dev.androidagent.core.widget.WidgetSpec
import dev.androidagent.core.widget.splitMessage

/**
 * An assistant reply: prose as Markdown, and every `mike-widget` block as a
 * live widget between it. A reply with no widget is drawn exactly as before.
 *
 * [onAsk] is how a widget's "ask" button writes back into the chat; null
 * disables those buttons.
 */
@Composable
internal fun AssistantMessageBody(
    messageId: String,
    text: String,
    streaming: Boolean,
    textColor: Color,
    onLongPress: () -> Unit,
    onAsk: ((String) -> Unit)?,
) {
    // A streaming reply is split at every token; keep the widgets already parsed.
    val parsed = remember(messageId) { HashMap<String, WidgetSpec>() }
    val parts = remember(text, streaming) {
        splitMessage(text, streaming) { source -> parsed.getOrPut(source) { WidgetSpec.parse(source) } }
    }
    val only = parts.singleOrNull()
    if (only is MessagePart.Markdown) {
        MarkdownMessage(text, textColor, onLongPress = onLongPress)
        return
    }
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { position, part ->
            key(position) {
                when (part) {
                    is MessagePart.Markdown -> MarkdownMessage(part.text, textColor, onLongPress = onLongPress)
                    is MessagePart.Widget -> WidgetView(part.spec, "$messageId/${part.index}/${part.source.hashCode()}", onAsk)
                    MessagePart.Pending -> WidgetSkeleton()
                    is MessagePart.Broken -> WidgetBroken(part.source, part.reason)
                }
            }
        }
    }
}
