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

package dev.androidagent.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** One locally advertised Wireless Debugging service. */
data class DiscoveredAdbService(
    val serviceName: String,
    val host: String?,
    val port: Int,
    val serviceType: String = "_adb-tls-connect._tcp."
)

/**
 * Small lifecycle-safe wrapper around Android NSD.
 *
 * Pairing and normal connection advertisements are always discovered with
 * separate listeners. The pairing port is temporary and is never returned as
 * a normal connection endpoint.
 */
class AdbServiceDiscovery(context: Context) {

    companion object {
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp."
        private const val LOOPBACK = "127.0.0.1"
        // Also read by AdbAutoConnectPlan's budget test, which has to know what one
        // discovery pass costs.
        internal const val DEFAULT_TIMEOUT_MS = 3_000L
        private val DIRECT_EXECUTOR = Executor { it.run() }

        fun isValidAdbPort(port: Int): Boolean = port in 1024..65535

        fun mergeService(
            current: List<DiscoveredAdbService>,
            service: DiscoveredAdbService
        ): List<DiscoveredAdbService> =
            (current.filterNot { it.serviceName == service.serviceName } + service)
                .sortedBy { it.serviceName }

        fun removeService(
            current: List<DiscoveredAdbService>,
            serviceName: String
        ): List<DiscoveredAdbService> = current.filterNot { it.serviceName == serviceName }
    }

    private val nsd =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Discovers both Wireless Debugging service types for a short bounded
     * window. Results are normalized to loopback because this app connects to
     * the adbd endpoint on the same phone.
     */
    suspend fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<DiscoveredAdbService> {
        val perTypeTimeout = (timeoutMs / 2).coerceIn(250L, 5_000L)
        // Run both listeners at once so a visible pairing dialog does not delay
        // discovery of an already paired connection endpoint.
        val connectResult = CompletableDeferred<List<DiscoveredAdbService>>()
        val pairingResult = CompletableDeferred<List<DiscoveredAdbService>>()
        val connectJob = scope.launch {
            connectResult.complete(discoverType(SERVICE_TYPE_CONNECT, perTypeTimeout))
        }
        val pairingJob = scope.launch {
            pairingResult.complete(discoverType(SERVICE_TYPE_PAIRING, perTypeTimeout))
        }
        try {
            val all = connectResult.await() + pairingResult.await()
            return all.distinctBy { Triple(it.serviceName, it.port, it.host) }
                .sortedWith(compareBy<DiscoveredAdbService> { it.serviceName }.thenBy { it.port })
        } finally {
            connectJob.cancelAndJoin()
            pairingJob.cancelAndJoin()
        }
    }

    private suspend fun discoverType(
        serviceType: String,
        timeoutMs: Long
    ): List<DiscoveredAdbService> = suspendCancellableCoroutine { continuation ->
        val session = Session(serviceType, timeoutMs, continuation)
        continuation.invokeOnCancellation { session.stop() }
        session.start()
    }

    private inner class Session(
        private val serviceType: String,
        private val timeoutMs: Long,
        private val continuation: kotlin.coroutines.Continuation<List<DiscoveredAdbService>>
    ) {
        private val lock = Any()
        private val services = linkedMapOf<String, DiscoveredAdbService>()
        private val infoCallbacks = linkedMapOf<String, NsdManager.ServiceInfoCallback>()
        private val tokens = linkedMapOf<String, Any>()
        private var stopped = false
        private var listenerRegistered = false
        private var finishJob: Job? = null

        private val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit

            override fun onDiscoveryStopped(type: String) = Unit

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                finish()
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!isWanted(info)) return
                val name = info.serviceName
                synchronized(lock) {
                    if (stopped || tokens.containsKey(name)) return
                    tokens[name] = Any()
                }
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        val callback = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceUpdated(updated: NsdServiceInfo) {
                                publish(updated, name)
                            }

                            override fun onServiceLost() {
                                synchronized(lock) { services.remove(name) }
                            }

                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                synchronized(lock) {
                                    infoCallbacks.remove(name)
                                    tokens.remove(name)
                                }
                            }

                            override fun onServiceInfoCallbackUnregistered() = Unit
                        }
                        synchronized(lock) { infoCallbacks[name] = callback }
                        nsd.registerServiceInfoCallback(info, DIRECT_EXECUTOR, callback)
                    } else {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                synchronized(lock) { tokens.remove(name) }
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                publish(serviceInfo, name)
                            }
                        })
                    }
                } catch (_: Exception) {
                    synchronized(lock) { tokens.remove(name) }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) {
                    tokens.remove(info.serviceName)
                    services.remove(info.serviceName)
                    if (Build.VERSION.SDK_INT >= 34) {
                        infoCallbacks.remove(info.serviceName)?.let {
                            runCatching { nsd.unregisterServiceInfoCallback(it) }
                        }
                    }
                }
            }
        }

        fun start() {
            try {
                synchronized(lock) {
                    if (stopped) return
                    listenerRegistered = true
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                }
                finishJob = scope.launch {
                    delay(timeoutMs)
                    finish()
                }
            } catch (_: Exception) {
                finish()
            }
        }

        fun stop() {
            synchronized(lock) {
                if (stopped) return
                stopped = true
                finishJob?.cancel()
                finishJob = null
                infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                infoCallbacks.clear()
                tokens.clear()
                if (listenerRegistered) {
                    runCatching { nsd.stopServiceDiscovery(listener) }
                    listenerRegistered = false
                }
            }
        }

        private fun finish() {
            val result: List<DiscoveredAdbService>
            synchronized(lock) {
                if (stopped) return
                result = services.values.toList()
            }
            stop()
            continuation.resume(result)
        }

        private fun publish(info: NsdServiceInfo, name: String) {
            val port = info.port
            if (!isValidAdbPort(port)) return
            val resolvedHost = info.host?.hostAddress?.substringBefore('%')
            if (!isLocalHost(resolvedHost)) return
            if (serviceType == SERVICE_TYPE_CONNECT && !isReachableLocalPort(port)) return
            synchronized(lock) {
                if (stopped || !tokens.containsKey(name)) return
                services[name] = DiscoveredAdbService(name, LOOPBACK, port, serviceType)
            }
        }

        private fun isWanted(info: NsdServiceInfo): Boolean =
            info.serviceType.trimEnd('.') == serviceType.trimEnd('.')
    }

    private fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (host == LOOPBACK || host == "0.0.0.0" || host == "::1") return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .contains(host)
        }.getOrDefault(false)
    }

    private fun isReachableLocalPort(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 350) }
        true
    }.getOrDefault(false)

    private fun <T> java.util.Enumeration<T>.toList(): List<T> {
        val result = mutableListOf<T>()
        while (hasMoreElements()) result += nextElement()
        return result
    }

}
