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

import dev.androidagent.core.AdbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLProtocolException

class AdbReconnectPolicyTest {
    @Test fun savedConnectPortWinsOverDiscoveredPairingAndConnectPorts() {
        val endpoints = listOf(
            AdbEndpoint(37123, pairing = true),
            AdbEndpoint(41231, pairing = false),
        )
        assertEquals(49876, AdbReconnectPolicy.preferredConnectPort(49876, endpoints))
        assertEquals(41231, AdbReconnectPolicy.preferredConnectPort(null, endpoints))
    }

    @Test fun pairingEndpointIsNeverSelectedAsTheReconnectTarget() {
        assertNull(AdbReconnectPolicy.preferredConnectPort(null, listOf(AdbEndpoint(37123, pairing = true))))
        assertEquals(41231, AdbReconnectPolicy.preferredConnectPort(80, listOf(AdbEndpoint(41231, pairing = false))))
        assertEquals(41231, AdbReconnectPolicy.fallbackConnectPort(49876, listOf(AdbEndpoint(41231, pairing = false))))
        assertNull(AdbReconnectPolicy.fallbackConnectPort(41231, listOf(AdbEndpoint(41231, pairing = false))))
    }

    @Test fun retryBackoffIsBounded() {
        assertEquals(500L, AdbReconnectPolicy.retryDelayMs(0))
        assertEquals(1_000L, AdbReconnectPolicy.retryDelayMs(1))
        assertEquals(10_000L, AdbReconnectPolicy.retryDelayMs(5))
        assertEquals(10_000L, AdbReconnectPolicy.retryDelayMs(100))
    }

    @Test fun missingServiceExplainsWirelessDebuggingState() {
        assertEquals("Wireless Debugging is off", AdbReconnectPolicy.noServiceMessage(false))
        assertEquals("Wireless Debugging is on; waiting for the ADB service", AdbReconnectPolicy.noServiceMessage(true))
        assertEquals("Wireless Debugging status unavailable; waiting for the ADB service", AdbReconnectPolicy.noServiceMessage(null))
    }

    @Test fun aRefusedKeyIsToldApartFromAClosedPort() {
        assertTrue(AdbReconnectPolicy.isPairingRejected(com.flyfishxu.kadb.exception.AdbPairAuthException()))
        // Wrapped, the way connect() rethrows it.
        assertTrue(AdbReconnectPolicy.isPairingRejected(IOException("Connection failed", com.flyfishxu.kadb.exception.AdbAuthException())))
        // What a Nothing A059 on Android 16 threw once adbd had dropped the key.
        assertTrue(
            AdbReconnectPolicy.isPairingRejected(
                SSLProtocolException(
                    "Read error: ssl=0xb400007878f6c3d8: Failure in SSL library, usually a protocol error\n" +
                        "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN",
                ),
            ),
        )
        // The stale port after Wireless debugging restarts, and a slow adbd.
        assertFalse(AdbReconnectPolicy.isPairingRejected(ConnectException("failed to connect to /127.0.0.1 (port 33453)")))
        assertFalse(AdbReconnectPolicy.isPairingRejected(IOException("TLS handshake timeout", SocketTimeoutException())))
    }
}
