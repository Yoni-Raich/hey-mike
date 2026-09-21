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

package dev.androidagent.app.ime

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.androidagent.core.*
import dev.androidagent.devicetools.AndroidDeviceTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 31)
class UnicodeImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String {
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use {
            it.write(command)
            it.newLine()
        }
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
    }

    @Test fun gatewayInsertsHebrewEmojiExactlyOnceIntoExternalEditorAndRestoresIme() = runBlocking {
        val pkg = instrumentation.targetContext.packageName
        val fixture = instrumentation.context.packageName + "/" + ImeEditorFixture::class.java.name
        val component = "$pkg/dev.androidagent.app.ime.AgentInputMethodService"
        val previous = shell("settings get secure default_input_method").trim()
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == component }
        val device = UiDevice.getInstance(instrumentation)
        val transport = object : AdbTransport {
            override val status = MutableStateFlow(AdbStatus(ConnectionPhase.CONNECTED))
            val commits = mutableListOf<String>()
            var legacyReceiverPermission = true
            override suspend fun execute(command: String, timeoutMs: Long): CommandResult {
                if (command.contains("INPUT_TEXT")) commits += "commit"
                val actual = if (legacyReceiverPermission && command.contains("INPUT_TEXT")) {
                    command.replace("am broadcast", "am broadcast --receiver-permission android.permission.DUMP")
                } else command
                val result = shell("$actual; echo __IME_EXIT__\$?")
                val code = result.substringAfterLast("__IME_EXIT__").trim().toIntOrNull() ?: -1
                return CommandResult(result.substringBeforeLast("__IME_EXIT__"), code)
            }
            override suspend fun discover(): List<AdbEndpoint> = emptyList()
            override suspend fun pair(port: Int, code: String) = Unit
            override suspend fun connect(port: Int) = Unit
            override suspend fun executeBytes(command: String, timeoutMs: Long) = byteArrayOf()
            override suspend fun cancelActive() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun forgetPairing() = Unit
        }
        try {
            shell("am start -W -n $fixture")
            val editor = device.wait(Until.findObject(By.res("android:id/edit")), 5000)
            assertNotNull("External fixture editor was not opened", editor)
            editor.click()
            val gateway = AndroidDeviceTools(transport, component)
            gateway.beginRun("ime-integration", instrumentation.targetContext.cacheDir)
            val text = "זה אתה (luna) 😀\nזה עובד!!!"
            try {
                gateway.invoke("type_text", buildJsonObject { put("text", text) })
                fail("Legacy receiver permission should block this app's IME")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("IME receiver did not respond"))
            }
            assertFalse(device.hasObject(By.text(text)))
            transport.legacyReceiverPermission = false
            transport.commits.clear()
            val result = gateway.invoke("type_text", buildJsonObject { put("text", text) })
            assertTrue(result.success)
            assertTrue("Text was not inserted exactly once", device.wait(Until.hasObject(By.text(text)), 5000))
            assertEquals(1, transport.commits.size)
            assertEquals(previous, shell("settings get secure default_input_method").trim())
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
            if (!wasEnabled) shell("ime disable $component")
            device.pressHome()
        }
    }
}
