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

package dev.androidagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexAccountVaultTest {
    @get:Rule val temp = TemporaryFolder()

    private val home by lazy { temp.newFolder("codex") }
    private val vault by lazy { CodexAccountVault(home, File(temp.root, "accounts")) }
    private val live get() = File(home, "auth.json")

    @Test fun `nothing is captured without credentials`() {
        assertNull(vault.captureActive("a@example.com"))
        assertTrue(vault.state().accounts.isEmpty())
    }

    @Test fun `capturing the same email twice keeps one account`() {
        live.writeText("token-a1")
        val first = vault.captureActive("a@example.com")!!
        live.writeText("token-a2")
        val second = vault.captureActive("A@example.com")!!
        assertEquals(first.id, second.id)
        assertEquals(1, vault.state().accounts.size)
        assertEquals(first.id, vault.state().activeId)
    }

    @Test fun `adding a second account and switching swaps only the credentials`() {
        File(home, "sessions").mkdirs()
        File(home, "sessions/rollout.jsonl").writeText("chat history")
        live.writeText("token-a")
        val a = vault.captureActive("a@example.com")!!

        vault.detach()
        assertFalse(live.exists())
        assertNull(vault.state().activeId)

        live.writeText("token-b")
        val b = vault.captureActive("b@example.com")!!
        assertEquals(b.id, vault.state().activeId)

        vault.activate(a.id)
        assertEquals("token-a", live.readText())
        assertEquals(a.id, vault.state().activeId)
        vault.activate(b.id)
        assertEquals("token-b", live.readText())
        assertEquals("chat history", File(home, "sessions/rollout.jsonl").readText())
    }

    @Test fun `switching away keeps the tokens Codex refreshed`() {
        live.writeText("token-a")
        val a = vault.captureActive("a@example.com")!!
        vault.detach()
        live.writeText("token-b")
        val b = vault.captureActive("b@example.com")!!
        live.writeText("token-b-refreshed")

        vault.activate(a.id)
        vault.activate(b.id)
        assertEquals("token-b-refreshed", live.readText())
    }

    @Test fun `removing the live account leaves its credentials to sign-out`() {
        live.writeText("token-a")
        val a = vault.captureActive("a@example.com")!!
        vault.remove(a.id)
        assertTrue(vault.state().accounts.isEmpty())
        assertNull(vault.state().activeId)
        assertEquals("token-a", live.readText())
    }

    @Test(expected = IllegalStateException::class)
    fun `activating an unknown account fails`() {
        vault.activate("missing")
    }

    @Test fun `a lent sign-in comes back with the tokens the side run refreshed`() {
        live.writeText("token-a")
        val a = vault.captureActive("a@example.com")!!
        vault.detach()
        live.writeText("token-b")
        vault.captureActive("b@example.com")!!

        val borrowed = File(temp.root, "side/.codex/auth.json")
        vault.lend(a.id, borrowed)
        assertEquals("token-a", borrowed.readText())
        borrowed.writeText("token-a-refreshed")
        vault.takeBack(a.id, borrowed)

        vault.activate(a.id)
        assertEquals("token-a-refreshed", live.readText())
    }

    @Test fun `the live sign-in is never lent and a taken-back copy never overwrites it`() {
        live.writeText("token-a")
        val a = vault.captureActive("a@example.com")!!
        val borrowed = File(temp.root, "side/auth.json")
        assertThrows(IllegalStateException::class.java) { vault.lend(a.id, borrowed) }
        assertFalse(borrowed.exists())

        borrowed.parentFile!!.mkdirs()
        borrowed.writeText("stale")
        vault.takeBack(a.id, borrowed)
        vault.detach()
        vault.activate(a.id)
        assertEquals("token-a", live.readText())
    }

    @Test fun `taking back for a removed account does nothing`() {
        live.writeText("token-a")
        val a = vault.captureActive("a@example.com")!!
        vault.detach()
        val borrowed = File(temp.root, "side/auth.json")
        vault.lend(a.id, borrowed)
        vault.remove(a.id)
        vault.takeBack(a.id, borrowed)
        assertFalse(File(temp.root, "accounts/${a.id}.auth.json").exists())
    }
}
