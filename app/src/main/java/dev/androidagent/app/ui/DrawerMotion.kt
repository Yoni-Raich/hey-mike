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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// Opening the panel does not cover the chat, it makes room for it. The chat
// steps back and to the side, rounding into a card as it goes, and the panel
// arrives in the space it left — so you never lose where you were, and closing
// hands the screen back the same way rather than cutting.
//
// It rides on the drawer's target rather than on the sheet's live offset: the
// target is what the menu button sets the moment it is tapped, so the push
// starts with the sheet instead of after it, and a drag still carries the chat
// along once it crosses the anchor. The price is that mid-drag the chat is
// animating towards where the drag is going rather than tracking the finger,
// which is invisible on a tap and slight on a drag.

/** How far the panel has taken the screen: 0 is away, 1 is fully arrived. */
@Composable
internal fun rememberDrawerPush(drawerState: DrawerState): State<Float> {
    // Animations off is a system-wide answer about motion: the panel still
    // opens, the chat simply does not move.
    if (!animationsEnabled()) return remember { mutableFloatStateOf(0f) }
    return animateFloatAsState(
        targetValue = if (drawerState.targetValue == DrawerValue.Open) 1f else 0f,
        // Arrives on the app's easing, leaves a little quicker, because the
        // sheet is already gone and a chat still settling reads as lag.
        animationSpec = tween(if (drawerState.targetValue == DrawerValue.Open) 340 else 260, easing = Emphasized),
        label = "drawer-push",
    )
}

/**
 * Steps the chat back and aside with [progress], read at draw time so the push
 * never recomposes the chat. It slides [shift] towards the edge the panel does
 * not come from, shrinks to [scaleTo], and rounds to [corner] so what is left
 * showing beside the panel reads as a card rather than a cut-off screen.
 */
internal fun Modifier.drawerPushed(
    progress: State<Float>,
    layoutDirection: LayoutDirection,
    shift: Dp = 64.dp,
    scaleTo: Float = 0.88f,
    corner: Dp = 28.dp,
): Modifier = graphicsLayer {
    val pushed = progress.value
    if (pushed <= 0f) {
        // Untouched when the panel is away: no clip, no layer transform, so the
        // chat is exactly what it was before any of this existed.
        clip = false
        return@graphicsLayer
    }
    val away = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    translationX = away * pushed * shift.toPx()
    val scale = 1f - (1f - scaleTo) * pushed
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 0.5f)
    shape = RoundedCornerShape(corner * pushed)
    clip = true
}
