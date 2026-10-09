// Hey Mike. Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
package dev.androidagent.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Context delivery finishes before either transport is allowed to open audio. */
internal suspend fun prepareVoiceInput(deliverContext: suspend () -> Unit) {
    currentCoroutineContext().ensureActive()
    deliverContext()
    currentCoroutineContext().ensureActive()
}
