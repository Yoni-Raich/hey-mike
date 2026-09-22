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

package dev.androidagent.a11y

import dev.androidagent.core.UiNode
import dev.androidagent.core.UiObservation
import dev.androidagent.core.UiObservationSerializer
import dev.androidagent.core.UiRange

/**
 * One window handed to the traversal, in front-to-back order.
 *
 * @param active the window the user is interacting with, used for
 *   `activePackage`. More reliable than counting package occurrences.
 */
data class A11yWindow(val root: A11yNodeView?, val active: Boolean, val type: String = "application")

/** The traversal result plus the node handles the gateway needs to act on. */
class TraversalResult(
    val observation: UiObservation,
    /** nodeId to the view it came from, for acting without re-reading the screen. */
    val handles: Map<String, A11yNodeView>,
)

/**
 * Flattens the visible accessibility tree into the shared observation model.
 *
 * Pure over [A11yNodeView] so it can be tested against fake trees; nothing in
 * here touches the platform.
 *
 * Our own windows and nodes are dropped. The agent must never see, and so can
 * never tap, the floating control card, the chat UI or the agent IME — the
 * overlay stays in the window manager even when it is hidden from screenshots,
 * so filtering it out of the tree is the only thing that keeps it out.
 */
fun traverse(windows: List<A11yWindow>, ownPackage: String): TraversalResult {
    val nodes = mutableListOf<UiNode>()
    val handles = mutableMapOf<String, A11yNodeView>()
    val packages = mutableMapOf<String, Int>()
    var activePackage: String? = null
    var visited = 0
    var treeTruncated = false
    // Ids come from a counter over every visited node, not from the emitted
    // list, so two different nodes can never share one and a clickableAncestor
    // always names the node it was taken from.
    var nextId = 0

    for (window in windows) {
        val root = window.root ?: continue
        if (root.packageName == ownPackage) continue
        if (window.active) {
            activePackage = activePackage ?: root.packageName
        }

        // Explicit stack rather than recursion, bounded by depth and by a
        // total node budget: a malformed or cyclic tree must cost the budget
        // rather than the call stack. Depth is the cycle guard, because node
        // identity is not something the platform promises.
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, null, null, 0))

        while (stack.isNotEmpty()) {
            if (visited >= UiObservationSerializer.MAX_UI_NODES) { treeTruncated = true; break }
            val (view, clickableAncestor, parentId, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) { treeTruncated = true; continue }
            visited++

            if (!view.isVisibleToUser) continue
            if (view.packageName == ownPackage) continue

            val node = UiNode(
                nodeId = "n${nextId++}",
                text = UiObservationSerializer.compactField(view.text),
                contentDescription = UiObservationSerializer.compactField(view.contentDescription),
                resourceId = UiObservationSerializer.compactField(view.viewIdResourceName),
                className = UiObservationSerializer.compactField(view.className),
                bounds = view.boundsInScreen,
                enabled = view.isEnabled,
                clickable = view.isClickable,
                scrollable = view.isScrollable,
                focused = view.isFocused,
                packageName = UiObservationSerializer.compactField(view.packageName),
                editable = view.isEditable,
                selected = view.isSelected,
                range = if (view.rangeMin != null && view.rangeMax != null && view.rangeCurrent != null) {
                    UiRange(
                        min = view.rangeMin!!.toDouble(),
                        max = view.rangeMax!!.toDouble(),
                        current = view.rangeCurrent!!.toDouble(),
                        type = view.rangeType,
                    )
                } else {
                    null
                },
                supportsSetProgress = view.supportsSetProgress,
                longClickable = view.isLongClickable,
                actions = view.actionNames,
                windowType = window.type,
                password = view.isPassword,
                checkable = view.isCheckable,
                checked = view.isChecked,
                clickableAncestor = clickableAncestor,
                parentId = parentId,
            )
            if (node.isMeaningful()) {
                nodes += node
                handles[node.nodeId] = view
                node.packageName?.let { packages[it] = (packages[it] ?: 0) + 1 }
            }

            // A labelled child that is not itself clickable needs its nearest
            // clickable parent, so the model has something real to tap.
            val nextAncestor = if (view.isClickable) node.asClickTarget() else clickableAncestor
            // Skipped nodes are not in the emitted list, so a child inherits
            // the nearest ancestor that *was* emitted. That keeps a subtree
            // query resolvable from the flat list alone.
            val nextParentId = if (node.isMeaningful()) node.nodeId else parentId
            for (index in view.childCount - 1 downTo 0) {
                view.child(index)?.let { stack.addLast(Frame(it, nextAncestor, nextParentId, depth + 1)) }
            }
        }
    }

    return TraversalResult(
        observation = UiObservation(
            activePackage = activePackage ?: packages.maxByOrNull { it.value }?.key,
            nodes = nodes,
            treeTruncated = treeTruncated,
        ),
        handles = handles,
    )
}

private data class Frame(
    val view: A11yNodeView,
    val clickableAncestor: UiNode?,
    /** Nearest ancestor that was emitted, for `rootNodeId` subtree queries. */
    val parentId: String?,
    val depth: Int,
)

/** Matches the depth ceiling the uiautomator XML parser already enforces. */
private const val MAX_DEPTH = 128
