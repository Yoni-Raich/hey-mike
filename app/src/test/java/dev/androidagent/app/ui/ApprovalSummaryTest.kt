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

import dev.androidagent.core.EngineEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalSummaryTest {

    private fun approval(uri: String, pkg: String? = null, reason: String = "Open a prefilled message.") =
        EngineEvent.Approval(
            requestId = "r",
            method = "open_intent",
            details = buildJsonObject {
                put("reason", reason)
                put("action", "android.intent.action.VIEW")
                put("uri", uri)
                pkg?.let { put("package", it) }
            },
        )

    @Test fun aWhatsAppMessageNamesTheRecipientAndTheDecodedText() {
        // The card that failed on the phone showed this exact request as raw JSON.
        val summary = approval("https://wa.me/972587160002?text=%D7%94%D7%99%D7%99", "com.whatsapp").summary()
        assertEquals("Send a WhatsApp message?", summary.headline)
        assertEquals(listOf("To" to "+972587160002", "Message" to "היי"), summary.lines)
    }

    @Test fun aComputerCommandShowsTheCommandAndWhere() {
        val summary = EngineEvent.Approval(
            requestId = "remote|pc|4",
            method = "item/commandExecution/requestApproval",
            details = buildJsonObject {
                put("command", "npm install")
                put("cwd", "C:\\src\\app")
                put("reason", "Install the project's packages")
            },
        ).summary()
        assertEquals("Run this on the computer?", summary.headline)
        assertEquals(
            listOf("Command" to "npm install", "In" to "C:\\src\\app", "Why" to "Install the project's packages"),
            summary.lines,
        )
    }

    @Test fun claudeOnAComputerAsksInTheSameWordsAsCodex() {
        val command = EngineEvent.Approval(
            requestId = "remote-claude|pc|claude-ask-1",
            method = "claude/can_use_tool",
            details = buildJsonObject {
                put("tool", "Bash")
                put("kind", "command")
                put("command", "curl -s https://example.com -o out.html")
                put("reason", "Fetch https://example.com and save to out.html")
                put("cwd", "C:\\src\\app")
            },
        ).summary()
        assertEquals("Run this on the computer?", command.headline)
        assertEquals(
            listOf(
                "Command" to "curl -s https://example.com -o out.html",
                "In" to "C:\\src\\app",
                "Why" to "Fetch https://example.com and save to out.html",
            ),
            command.lines,
        )

        val file = EngineEvent.Approval(
            requestId = "remote-claude|pc|claude-ask-2",
            method = "claude/can_use_tool",
            details = buildJsonObject { put("tool", "Write"); put("kind", "file"); put("path", "C:\\Windows\\notes.txt") },
        ).summary()
        assertEquals("Change this file on the computer?", file.headline)
        assertEquals(listOf("File" to "C:\\Windows\\notes.txt"), file.lines)

        val other = EngineEvent.Approval(
            requestId = "remote-claude|pc|claude-ask-3",
            method = "claude/can_use_tool",
            details = buildJsonObject { put("tool", "WebFetch"); put("kind", "tool"); put("input", "{\"url\":\"https://example.com\"}") },
        ).summary()
        assertEquals("Let Mike use WebFetch on the computer?", other.headline)
    }

    @Test fun anIntentWithNoUriNamesItsAction() {
        val summary = EngineEvent.Approval(
            requestId = "p",
            method = "open_intent",
            details = buildJsonObject {
                put("reason", "Start a payment. (AMOUNT=10)")
                put("action", "com.example.pay.CHECKOUT")
                put("package", "com.example.pay")
            },
        ).summary()
        assertEquals("Open this?", summary.headline)
        assertEquals(
            listOf("What" to "Start a payment. (AMOUNT=10)", "Action" to "CHECKOUT", "App" to "com.example.pay"),
            summary.lines,
        )
    }

    @Test fun computerPermissionsNameTheRequestedAccessAndItsDuration() {
        val summary = EngineEvent.Approval(
            requestId = "remote|pc|4",
            method = "item/permissions/requestApproval",
            details = Json.parseToJsonElement(
                """{"reason":"Copy the report","cwd":"C:\\project","permissions":{"network":{"enabled":true},"fileSystem":{"read":["C:\\reports"],"write":["C:\\shared"],"entries":[{"path":{"type":"path","path":"D:\\output"},"access":"write"}]}}}""",
            ).jsonObject,
        ).summary()
        assertEquals(
            listOf("Why" to "Copy the report", "In" to "C:\\project", "Network" to "Allow access",
                "Read files" to "C:\\reports", "Change files" to "C:\\shared", "Change files" to "D:\\output", "For" to "This turn only"),
            summary.lines,
        )
    }

    @Test fun anSmsShowsTheNumberAndBody() {
        val summary = approval("smsto:+972500000000?body=on%20my%20way%20%26%20close").summary()
        assertEquals("Send an SMS?", summary.headline)
        assertEquals(listOf("To" to "+972500000000", "Message" to "on my way & close"), summary.lines)
    }

    @Test fun aSendNamesTheChatAndOffersBothAlwaysChoices() {
        val summary = EngineEvent.Approval(
            requestId = "s",
            method = "send_message",
            details = buildJsonObject {
                put("kind", "send")
                put("app", "WhatsApp")
                put("package", "com.whatsapp")
                put("recipient", "My Wife")
                put("message", "hi")
            },
        ).summary()
        assertEquals("Send this WhatsApp message?", summary.headline)
        assertEquals(listOf("To" to "My Wife", "Message" to "hi"), summary.lines)
        assertEquals("WhatsApp", summary.sendApp)
        assertEquals("My Wife", summary.sendRecipient)
    }

    @Test fun anythingElseFallsBackToTheReasonAndLink() {
        val summary = approval("https://pay.example/?amount=10", reason = "Start a payment.").summary()
        assertEquals("Open this?", summary.headline)
        assertTrue(summary.lines.contains("What" to "Start a payment."))
        assertTrue(summary.lines.contains("Link" to "https://pay.example/?amount=10"))
    }
}
