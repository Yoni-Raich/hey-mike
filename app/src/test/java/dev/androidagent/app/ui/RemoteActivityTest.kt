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

import dev.androidagent.core.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteActivityTest {
    // The coordinator stores a Codex item as "title\n\ndetail", state "streaming" | "complete" | "failed".
    private fun step(title: String, detail: String = "", state: String = "complete", live: Boolean = false) =
        activityStep(ChatMessage("m", "s", "remote_activity", "$title\n\n$detail", 0L, state), live)

    @Test fun aCommandSaysWhatRanNotWhichToolRanIt() {
        val done = step("Command execution", "ls -la /media/tv\nExit code: 0")
        assertEquals("Ran ls -la /media/tv", done.label)
        assertEquals(ActivityKind.COMMAND, done.kind)
        assertEquals(StepState.DONE, done.state)
        assertEquals("Running ls -la /media/tv", step("Command execution", "ls -la /media/tv", "streaming", live = true).label)
        assertEquals("Ran a command", step("Command execution").label)
    }

    @Test fun theShellCodexWrapsACommandInIsNotPartOfWhatItDid() {
        assertEquals("ls -la /media/tv", shortCommand("/bin/bash -lc 'ls -la /media/tv'"))
        assertEquals("cd /x && ls", shortCommand("bash -lc \"cd /x && ls\""))
        assertEquals("Get-ChildItem 'C:\\x'", shortCommand("powershell.exe -NoProfile -Command \"Get-ChildItem 'C:\\x'\""))
        assertEquals("Get-Date", shortCommand("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" -Command Get-Date"))
        assertEquals("dir", shortCommand("cmd.exe /c dir"))
        // A command that is not a shell wrapper reads as it is.
        assertEquals("ls -la", shortCommand("ls -la"))
        assertEquals("sh script.sh", shortCommand("sh script.sh"))
    }

    @Test fun aLongOrMultiLineCommandIsOneShortLine() {
        assertEquals("cd /x …", shortCommand("bash -lc 'cd /x\nls'"))
        val long = "echo " + "a".repeat(400)
        assertEquals(160, shortCommand(long).length)
        assertEquals("…", shortCommand(long).takeLast(1))
    }

    @Test fun thinkingReadsInTheTenseItIsIn() {
        assertEquals("Thinking…", step("Thinking", state = "streaming", live = true).label)
        assertEquals("Thought", step("Thinking").label)
        assertEquals(ActivityKind.THINKING, step("Thinking").kind)
    }

    @Test fun aStepANeverEndedRunLeftRunningWasStopped() {
        val stopped = step("Command execution", "make", "streaming", live = false)
        assertEquals(StepState.STOPPED, stopped.state)
        assertEquals("Stopped: make", stopped.label)
    }

    @Test fun aFailedStepSaysSo() {
        val failed = step("Command execution", "make\nExit code: 2", "failed")
        assertEquals(StepState.FAILED, failed.state)
        assertEquals("Failed: make", failed.label)
        assertEquals(StepState.FAILED, step("Command execution", "make", "interrupted").state)
    }

    @Test fun fileChangesNameTheFileOrCountThem() {
        assertEquals("Changed A.kt", step("File change", "C:\\repo\\src\\A.kt").label)
        assertEquals("Changed 2 files", step("File change", "src/A.kt\nsrc/B.kt").label)
        assertEquals("Changing files", step("File change", "", "streaming", live = true).label)
    }

    @Test fun otherItemsReadAsWhatTheyAre() {
        assertEquals("Used files.read", step("Tool: files.read").label)
        assertEquals("Searched for jellyfin subtitles", step("Web search", "jellyfin subtitles").label)
        assertEquals("Agent work", step("Agent work").label)
        assertEquals("Agent work failed", step("Agent work", state = "failed").label)
    }

    @Test fun theHeaderCountsWhatWasDoneAndWhere() {
        val steps = listOf(
            step("Command execution", "ls"), step("Thinking"), step("Command execution", "cat a"),
            step("File change", "a.txt"), step("Command execution", "rm b", "failed"),
        )
        val done = activityHeader(steps, "Server", live = false)
        assertEquals("Worked on Server · 3 commands, 1 file change", done.text)
        assertEquals("1 failed", done.failed)
        assertEquals("Worked on Server · 3 commands, 1 file change, 1 failed", done.spoken)
        assertEquals("Working on Server", activityHeader(steps, "Server", live = true).text)
        assertEquals("Worked · 1 command", activityHeader(listOf(step("Command execution", "ls")), null, live = false).text)
    }

    @Test fun aRunOfOnlyThinkingIsJustAThought() {
        val header = activityHeader(listOf(step("Thinking"), step("Thinking")), "Server", live = false)
        assertEquals("Thought", header.text)
        assertNull(header.failed)
    }
}
