// Hey Mike. Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
package dev.androidagent.core

/** Visible text captured before the assistant window is drawn; no actionable node IDs. */
data class AssistantScreenText(val packageName: String?, val lines: List<String>)
