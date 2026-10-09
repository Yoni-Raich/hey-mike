// Hey Mike. Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
package dev.androidagent.app.assist

/** One invocation switches from voice to text once, even during voice setup or paste. */
internal class AssistantInputMode {
    var typing = false
        private set

    fun startTyping(): Boolean {
        if (typing) return false
        typing = true
        return true
    }
}
