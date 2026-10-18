// Hey Mike. Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
package dev.androidagent.a11y

import dev.androidagent.core.AssistantScreenText

/** Bounded visible text only. Keep a long post intact instead of the UI tool's short labels. */
internal fun assistantScreenText(windows: List<A11yWindow>, ownPackage: String): AssistantScreenText? {
    val candidates = windows.filter { it.root?.packageName != ownPackage && it.root != null }
    val root = (candidates.firstOrNull { it.active } ?: candidates.firstOrNull())?.root ?: return null
    val stack = ArrayDeque<Pair<A11yNodeView, Int>>()
    stack.addLast(root to 0)
    val lines = mutableListOf<String>()
    var visited = 0
    var chars = 0
    while (stack.isNotEmpty() && visited++ < 4_000 && chars < 16_000) {
        val (node, depth) = stack.removeLast()
        if (depth > 64 || !node.isVisibleToUser || node.packageName == ownPackage || node.isPassword) continue
        if (!node.isPassword) {
            val text = node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription
            text?.takeIf { it.isNotBlank() }?.take(16_000 - chars)?.let { lines += it; chars += it.length }
        }
        for (i in node.childCount - 1 downTo 0) node.child(i)?.let { stack.addLast(it to depth + 1) }
    }
    return AssistantScreenText(root.packageName, lines)
}
