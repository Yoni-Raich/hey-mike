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

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

// The panel does not just appear. The surface arrives first, and its contents
// ride in from behind its own edge a beat later, each part on its own delay:
// the name, the new-chat button, then the rules strip, then the chats. The
// stagger is the point — it makes the strip read as the headline rather than
// one more row, and it is the same trick, and the same easing, that voice mode
// uses to take over the screen. Closing plays the order backwards, from the
// chats out to the name, so the panel leaves the way it came.

/** How far each part of the panel is into view: 1 is fully shown. */
@Stable
internal class DrawerMotion(
    val header: State<Float>,
    val newChat: State<Float>,
    val strip: State<Float>,
    val chats: State<Float>,
    val list: State<Float>,
)

@Composable
internal fun rememberDrawerMotion(open: Boolean): DrawerMotion {
    if (!animationsEnabled()) {
        // Animations off is a system-wide answer about motion, not a reason to
        // hide half the panel: every part starts and stays shown.
        val shown = remember { mutableFloatStateOf(1f) }
        return remember { DrawerMotion(shown, shown, shown, shown, shown) }
    }
    // Starts closed even when the first frame is already open, so a panel
    // restored with the process still plays its way in.
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = open
    val transition = updateTransition(visibility, label = "drawer")
    return DrawerMotion(
        header = transition.part(show = 80, hide = 90, label = "drawer-header"),
        newChat = transition.part(show = 130, hide = 70, label = "drawer-new-chat"),
        strip = transition.part(show = 180, hide = 50, label = "drawer-strip"),
        chats = transition.part(show = 225, hide = 30, label = "drawer-chats"),
        list = transition.part(show = 265, hide = 0, label = "drawer-list"),
    )
}

/**
 * One part's visibility. [show] and [hide] are its delays in each direction;
 * leaving is quicker than arriving, because the sheet is sliding out under it
 * and a part still fading when the panel is gone reads as a dropped frame.
 */
@Composable
private fun Transition<Boolean>.part(show: Int, hide: Int, label: String): State<Float> = animateFloat(
    transitionSpec = {
        if (targetState) {
            tween(340, delayMillis = show, easing = Emphasized)
        } else {
            tween(170, delayMillis = hide, easing = Emphasized)
        }
    },
    label = label,
) { open -> if (open) 1f else 0f }

/**
 * Moves a part with its [visible] progress, read at draw time so the cascade
 * never recomposes the panel. A hidden part sits [slide] away along the
 * panel's own axis — negative is towards the left edge, so the caller signs it
 * for the layout direction the drawer opens from.
 */
internal fun Modifier.drawerStage(visible: State<Float>, slide: Dp): Modifier = graphicsLayer {
    val shown = visible.value
    alpha = shown
    translationX = (1f - shown) * slide.toPx()
}
