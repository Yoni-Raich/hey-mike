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

import android.view.accessibility.AccessibilityNodeInfo

/**
 * The slice of an accessibility node the traversal actually reads.
 *
 * `AccessibilityNodeInfo` cannot be constructed off-device, so the traversal
 * is written against this instead and can be unit-tested against fake trees.
 * [RealNodeView] is the only production implementation.
 */
interface A11yNodeView {
    val text: String?
    val contentDescription: String?
    val viewIdResourceName: String?
    val className: String?
    val packageName: String?

    /** Left, top, right, bottom in screen pixels. */
    val boundsInScreen: List<Int>

    val isEnabled: Boolean
    val isClickable: Boolean
    val isScrollable: Boolean
    val isFocused: Boolean
    val isVisibleToUser: Boolean
    val isPassword: Boolean
    val isEditable: Boolean
    val isSelected: Boolean get() = false
    val rangeMin: Float? get() = null
    val rangeMax: Float? get() = null
    val rangeCurrent: Float? get() = null
    val rangeType: String? get() = null
    val supportsSetProgress: Boolean get() = false

    /** True for a switch, checkbox or radio. [isChecked] means nothing without it. */
    val isCheckable: Boolean
    val isChecked: Boolean

    val childCount: Int

    /** Null for a child the platform failed to materialise. */
    fun child(index: Int): A11yNodeView?
}

/** Wraps a live platform node. Holds no state of its own. */
class RealNodeView(val node: AccessibilityNodeInfo) : A11yNodeView {
    override val text: String? get() = node.text?.toString()
    override val contentDescription: String? get() = node.contentDescription?.toString()
    override val viewIdResourceName: String? get() = node.viewIdResourceName
    override val className: String? get() = node.className?.toString()
    override val packageName: String? get() = node.packageName?.toString()

    override val boundsInScreen: List<Int>
        get() {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            return listOf(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val isEnabled: Boolean get() = node.isEnabled
    override val isClickable: Boolean get() = node.isClickable
    override val isScrollable: Boolean get() = node.isScrollable
    override val isFocused: Boolean get() = node.isFocused
    override val isVisibleToUser: Boolean get() = node.isVisibleToUser
    override val isPassword: Boolean get() = node.isPassword
    override val isEditable: Boolean get() = node.isEditable
    override val isSelected: Boolean get() = node.isSelected
    override val rangeMin: Float? get() = node.rangeInfo?.min
    override val rangeMax: Float? get() = node.rangeInfo?.max
    override val rangeCurrent: Float? get() = node.rangeInfo?.current
    override val rangeType: String?
        get() = when (node.rangeInfo?.type) {
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT -> "int"
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT -> "float"
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT -> "percent"
            else -> null
        }
    override val supportsSetProgress: Boolean
        get() = node.actionList.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        }
    override val isCheckable: Boolean get() = node.isCheckable
    override val isChecked: Boolean get() = node.isChecked

    override val childCount: Int get() = node.childCount

    override fun child(index: Int): A11yNodeView? =
        runCatching { node.getChild(index) }.getOrNull()?.let(::RealNodeView)
}
