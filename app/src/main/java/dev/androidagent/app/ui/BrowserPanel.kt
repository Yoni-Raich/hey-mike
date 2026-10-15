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

package dev.androidagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.androidagent.web.BrowserFrame
import dev.androidagent.web.BrowserPageState

private val PanelBar = Color(0xFF1C1B19)
private val AgentInk = Color(0xFF69A7FF)
private val UserInk = Color(0xFFE8C9A0)
private val NotSecure = Color(0xFFFFB4A9)

/** The open chat's page fills the screen. */
internal fun AgentUiState.browserShown(): Boolean =
    browserPage != null && browserOpenFor != null && browserOpenFor == activeSessionId

/** Who has the page, in the words the user sees. */
internal fun browserControlLabel(page: BrowserPageState): String =
    if (page.userControlled) "You have control. Mike waits until you give it back."
    else "Mike is using this page. Take control to use it yourself."

/** The site, as the address bar and the chat card say it. */
internal fun browserSiteLabel(page: BrowserPageState): String {
    val host = page.host ?: return "Blank page"
    return if (page.secure) host else "Not secure · $host"
}

/**
 * This chat's page, pinned above the composer: which site, who has it, and
 * the way in. The page itself stays out of the conversation.
 */
@Composable
internal fun BrowserPageCard(page: BrowserPageState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val site = browserSiteLabel(page)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = PanelBar,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClickLabel = "Open the page", onClick = onOpen)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Language, null, Modifier.size(20.dp), tint = if (page.userControlled) UserInk else AgentInk)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(page.title ?: site, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (page.userControlled) "Your turn · " else "Mike's browser · ") + site,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (page.secure) MaterialTheme.colorScheme.onSurfaceVariant else NotSecure,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

/**
 * The chat's page on Hey Mike's own screen. The bar says which site this is,
 * because the user may type a password here, and who has the page. While
 * Mike has it the page shows but does not take touches.
 */
@Composable
internal fun BrowserPanel(
    page: BrowserPageState,
    running: Boolean,
    onBack: () -> Unit,
    onTakeControl: () -> Unit,
    onGiveBack: () -> Unit,
    onClosePage: () -> Unit,
    onStop: () -> Unit,
    attach: (BrowserFrame) -> Boolean,
    detach: (BrowserFrame) -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmClose by rememberSaveable(page.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            // The chat stays composed underneath; a touch anywhere here must not reach it.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth().background(PanelBar).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to the chat") }
            Column(Modifier.weight(1f)) {
                Text(page.title ?: "Mike's browser", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (page.secure) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null,
                        Modifier.size(12.dp), tint = if (page.secure) MaterialTheme.colorScheme.onSurfaceVariant else NotSecure,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        browserSiteLabel(page), style = MaterialTheme.typography.labelSmall,
                        color = if (page.secure) MaterialTheme.colorScheme.onSurfaceVariant else NotSecure,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { confirmClose = true }) { Icon(Icons.Outlined.Close, contentDescription = "Close the page") }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(PanelBar)
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = browserControlLabel(page) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = if (page.userControlled) UserInk else AgentInk, size = 8.dp, pulsing = !page.userControlled && running)
            Spacer(Modifier.width(10.dp))
            Text(
                if (page.userControlled) "You have control" else "Mike has the page",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
            )
            if (running && !page.userControlled) {
                TextButton(onClick = onStop) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            }
            if (page.userControlled) {
                FilledTonalButton(onClick = onGiveBack) { Text("Give back to Mike") }
            } else {
                OutlinedButton(onClick = onTakeControl) { Text("Take control") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrowserFrameView(page.key, attach, detach)
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close this page?") },
            text = { Text("Anything typed on it is gone. Mike can open the site again later.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; onClosePage() }) { Text("Close page") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep it") } },
        )
    }
}

/**
 * Holds the WebView while this screen is in front. When the activity stops,
 * the page goes back to its hidden window, which keeps drawing, so Mike can
 * go on working while the user is elsewhere.
 */
@Composable
private fun BrowserFrameView(key: String, attach: (BrowserFrame) -> Boolean, detach: (BrowserFrame) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val frame = remember(key) { mutableStateOf<BrowserFrame?>(null) }
    AndroidView(factory = { context -> BrowserFrame(context).also { frame.value = it } }, modifier = Modifier.fillMaxSize())
    val shown by frame
    DisposableEffect(owner, shown, key) {
        val target = shown ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> attach(target)
                Lifecycle.Event.ON_STOP -> detach(target)
                else -> Unit
            }
        }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attach(target)
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            detach(target)
        }
    }
}
