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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelGrantsTest {
    private fun call(arguments: String) = PanelCall(PanelAllowlist.TOOL, Json.parseToJsonElement(arguments).jsonObject)

    @Test fun eachCallNeedsWhatAndroidSystemPlatformChecks() {
        assertEquals(PanelGrants.WRITE_SETTINGS, PanelGrants.needed(call("""{"operation":"brightness","percent":30}""")))
        assertEquals(PanelGrants.WRITE_SETTINGS, PanelGrants.needed(call("""{"operation":"brightness","auto":true}""")))
        assertEquals(PanelGrants.WRITE_SETTINGS, PanelGrants.needed(call("""{"operation":"auto_rotate","on":false}""")))
        assertEquals(PanelGrants.WRITE_SETTINGS, PanelGrants.needed(call("""{"operation":"screen_timeout","seconds":30}""")))
        assertEquals(PanelGrants.NOTIFICATION_POLICY, PanelGrants.needed(call("""{"operation":"dnd","mode":"priority"}""")))
        assertEquals(PanelGrants.NOTIFICATION_POLICY, PanelGrants.needed(call("""{"operation":"dnd","mode":"off"}""")))
        assertEquals(PanelGrants.NOTIFICATION_POLICY, PanelGrants.needed(call("""{"operation":"ringer","mode":"Silent"}""")))
        // Only silent mode is checked first; volume needs it only when Android refuses, which no one knows before.
        assertNull(PanelGrants.needed(call("""{"operation":"ringer","mode":"vibrate"}""")))
        assertNull(PanelGrants.needed(call("""{"operation":"volume","stream":"ring","percent":0}""")))
        assertNull(PanelGrants.needed(call("""{"operation":"torch","on":true}""")))
        assertNull(PanelGrants.needed(call("""{"operation":"media_control","command":"play"}""")))
        assertNull(PanelGrants.needed(PanelCall("other_tool", Json.parseToJsonElement("""{"operation":"brightness"}""").jsonObject)))
    }

    @Test fun aFailedResultNamesItsMissingGrant() {
        val missing = """{"ok":false,"errorType":"grant_missing","message":"x","grant":"write_settings"}"""
        assertEquals(PanelGrants.WRITE_SETTINGS, PanelGrants.missing(missing))
        assertNull(PanelGrants.missing("""{"ok":false,"errorType":"write_failed","message":"x"}"""))
        assertNull(PanelGrants.missing("not json"))
        assertEquals("Hey Mike needs “Modify system settings”; allow it in Saved panels", PanelGrants.reason(PanelGrants.WRITE_SETTINGS))
        assertEquals("Do Not Disturb access", PanelGrants.name(PanelGrants.NOTIFICATION_POLICY))
    }

    @Test fun theApprovalLinesFlagOnlyTheStepsWhoseSwitchIsOff() {
        val panel = AgentPanels.parse(
            "p",
            Json.parseToJsonElement(
                """{"title":"Bedtime","surface":"widget","blocks":[
                {"type":"timer","id":"wind","label":"Lights out","seconds":1200},
                {"type":"action","id":"go","label":"Start bedtime","steps":[
                  {"tool":"system_controls","arguments":{"operation":"dnd","mode":"priority"}},
                  {"tool":"system_controls","arguments":{"operation":"brightness","percent":30}},
                  {"op":"start","target":"wind"},
                  {"tool":"system_controls","arguments":{"operation":"auto_rotate","on":false}}]},
                {"type":"action","id":"torch","label":"Torch","tool":"system_controls","arguments":{"operation":"torch","on":true}}]}""",
            ).jsonObject,
        ).getOrThrow()
        val lines = PanelApprovalLines.of(panel)
        val steps = lines.single { it.label == "Start bedtime" }
        assertEquals(
            listOf(
                PanelGrants.Need(PanelGrants.NOTIFICATION_POLICY, 1),
                PanelGrants.Need(PanelGrants.WRITE_SETTINGS, 2),
                PanelGrants.Need(PanelGrants.WRITE_SETTINGS, 4),
            ),
            steps.needs,
        )
        assertEquals(emptyList<PanelGrants.Need>(), lines.single { it.label == "Torch" }.needs)
        assertEquals(
            listOf("Steps 2 and 4 need “Modify system settings” for Hey Mike, which is off. It fails until you allow it."),
            PanelGrants.flags(steps.needs, setOf(PanelGrants.WRITE_SETTINGS)).map { it.second },
        )
        assertEquals(
            listOf(
                "Step 1 needs “Do Not Disturb access” for Hey Mike, which is off. It fails until you allow it.",
                "Steps 2 and 4 need “Modify system settings” for Hey Mike, which is off. It fails until you allow it.",
            ),
            PanelGrants.flags(steps.needs, setOf(PanelGrants.WRITE_SETTINGS, PanelGrants.NOTIFICATION_POLICY)).map { it.second },
        )
        assertEquals(emptyList<String>(), PanelGrants.flags(steps.needs, emptySet()).map { it.second })
    }
}
