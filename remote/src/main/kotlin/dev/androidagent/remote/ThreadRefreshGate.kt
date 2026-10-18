/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license from the copyright holder.
 * See LICENSE, LICENSE-COMMERCIAL.md and NOTICE. Provided without warranty.
 */
package dev.androidagent.remote

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Drop duplicate in-flight listings; drawer openings may reuse a recent attempt. */
internal class ThreadRefreshGate(
    private val quietIntervalNs: Long = 15_000_000_000L,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private class Slot {
        val lock = Mutex()
        var lastAttempt: Long? = null
    }
    private val slots = ConcurrentHashMap<String, Slot>()

    suspend fun run(computerId: String, force: Boolean, refresh: suspend () -> Unit): Boolean {
        val slot = slots.getOrPut(computerId) { Slot() }
        if (!slot.lock.tryLock()) return false
        try {
            val now = nowNs()
            if (!force && slot.lastAttempt?.let { now - it < quietIntervalNs } == true) return false
            slot.lastAttempt = now
            refresh()
            return true
        } finally {
            slot.lock.unlock()
        }
    }
}
