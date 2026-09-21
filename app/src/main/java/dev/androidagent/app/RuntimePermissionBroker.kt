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

package dev.androidagent.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Bridges a device-tool permission request to Android's one visible runtime
 * permission flow. The gateway validates the permission allowlist first; this
 * class only handles lifecycle and returns the permissions Android granted.
 */
class RuntimePermissionBroker(private val app: Application) {
    private data class Registration(
        val activity: WeakReference<Activity>,
        val launcher: ActivityResultLauncher<Array<String>>,
        val resumed: Boolean,
    )

    private val requestLock = Mutex()
    private val registration = MutableStateFlow<Registration?>(null)
    private var pending: CompletableDeferred<Set<String>>? = null

    fun attach(activity: Activity, launcher: ActivityResultLauncher<Array<String>>) {
        registration.value = Registration(WeakReference(activity), launcher, resumed = false)
    }

    fun resumed(activity: Activity) {
        registration.value?.takeIf { it.activity.get() === activity }?.let {
            registration.value = it.copy(resumed = true)
        }
    }

    fun paused(activity: Activity) {
        registration.value?.takeIf { it.activity.get() === activity }?.let {
            registration.value = it.copy(resumed = false)
        }
    }

    fun detach(activity: Activity) {
        if (registration.value?.activity?.get() === activity) registration.value = null
    }

    fun complete(result: Map<String, Boolean>) {
        pending?.complete(result.filterValues { it }.keys)
    }

    suspend fun request(permissions: Set<String>): Set<String> = requestLock.withLock {
        val missing = permissions.filterNot(::isGranted).toSet()
        if (missing.isEmpty()) return@withLock emptySet()

        // Snapshot foreground state before raising: only a broker-raised
        // screen steps back afterwards, so a workflow keeps driving its app.
        var raised = false
        try {
            withContext(Dispatchers.Main.immediate) {
                val state = registration.value
                raised = state == null || state.activity.get() == null || !state.resumed
                if (raised) {
                    app.startActivity(
                        Intent(app, MainActivity::class.java)
                            .putExtra(EXTRA_CAPABILITY_PERMISSION_REQUEST, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }

            // withTimeoutOrNull: an internal timeout answers empty, while an
            // external cancellation (Stop) still propagates instead of looking
            // like a denial.
            val ready = withTimeoutOrNull(ACTIVITY_TIMEOUT_MS) {
                registration.filterNotNull().first { state ->
                    val activity = state.activity.get()
                    state.resumed && activity != null && !activity.isFinishing && !activity.isDestroyed
                }
            }
            if (ready == null) return@withLock emptySet()

            val remaining = missing.filterNot(::isGranted).toSet()
            if (remaining.isEmpty()) return@withLock emptySet()

            val answer = CompletableDeferred<Set<String>>()
            withContext(Dispatchers.Main.immediate) {
                check(pending == null) { "A runtime permission request is already active." }
                pending = answer
                try {
                    ready.launcher.launch(remaining.toTypedArray())
                } catch (error: Exception) {
                    pending = null
                    throw error
                }
            }
            val granted: Set<String>?
            try {
                granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { answer.await() }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (pending === answer) pending = null
                }
            }
            // Timeout: report whatever Android granted meanwhile, possibly none.
            return@withLock granted ?: permissions.filter(::isGranted).toSet()
        } finally {
            if (raised) stepBack()
        }
    }

    /**
     * Move Mike's task back so the driven app is visible again. Only called
     * when this request raised the app; a screen the user already had open
     * stays where it is.
     */
    private suspend fun stepBack() {
        val activity = registration.value?.activity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            activity.moveTaskToBack(true)
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_CAPABILITY_PERMISSION_REQUEST = "dev.androidagent.app.extra.CAPABILITY_PERMISSION_REQUEST"
        private const val ACTIVITY_TIMEOUT_MS = 10_000L
        private const val PERMISSION_TIMEOUT_MS = 30_000L
    }
}
