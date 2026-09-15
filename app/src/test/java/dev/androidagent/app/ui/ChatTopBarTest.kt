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

package dev.androidagent.app.ui

import dev.androidagent.a11y.A11yStatus
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.ConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTopBarTest {
    @Test fun accessibilityAloneIsEnoughToControlThePhone() {
        val control = phoneControl(a11yConnected = true, adbPhase = ConnectionPhase.DISCONNECTED, running = false)
        assertEquals(ControlState.READY, control.state)
        assertEquals("Ready to control your phone through Accessibility", control.sentence())
    }

    @Test fun bothBackendsAreNamedAndAdbAloneCounts() {
        assertEquals("Accessibility and ADB", phoneControl(true, ConnectionPhase.CONNECTED, false).via)
        assertEquals(ControlState.READY, phoneControl(false, ConnectionPhase.CONNECTED, false).state)
    }

    @Test fun nothingConnectedIsBlockedEvenWhileReconnecting() {
        val control = phoneControl(a11yConnected = false, adbPhase = ConnectionPhase.CONNECTING, running = false)
        assertEquals(ControlState.BLOCKED, control.state)
        assertEquals("The agent cannot control your phone yet", control.sentence())
    }

    @Test fun aRunIsWorkingWithOrWithoutThePhone() {
        assertEquals("Working on your phone through Accessibility", phoneControl(true, ConnectionPhase.DISCONNECTED, true).sentence())
        assertEquals("Working on your request", phoneControl(false, ConnectionPhase.DISCONNECTED, true).sentence())
    }

    @Test fun backendNotesSayWhatToDo() {
        assertEquals("On · reads and taps apps directly", a11yNote(A11yStatus(declaredEnabled = true, connected = true)))
        assertEquals("Allow restricted settings to finish turning it on", a11yNote(A11yStatus(declaredEnabled = true, connected = false)))
        assertEquals("Off", a11yNote(A11yStatus(declaredEnabled = false, connected = false)))
        assertEquals("Connected · port 37123", adbNote(AdbStatus(ConnectionPhase.CONNECTED, "Connected", 37123)))
        assertEquals("Reconnecting", adbNote(AdbStatus(ConnectionPhase.CONNECTING, "Connecting")))
    }
}
