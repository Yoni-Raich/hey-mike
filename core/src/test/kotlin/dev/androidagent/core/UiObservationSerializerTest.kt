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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class UiObservationSerializerTest {

    private fun node(
        id: String = "n0",
        text: String? = "Send",
        contentDescription: String? = null,
        resourceId: String? = "com.example:id/send",
        className: String? = "android.widget.Button",
        bounds: List<Int>? = listOf(10, 20, 110, 60),
        password: Boolean = false,
        clickableAncestor: UiNode? = null,
        packageName: String = "com.example",
        clickable: Boolean = true,
        scrollable: Boolean = false,
        parentId: String? = null,
    ) = UiNode(
        nodeId = id,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        className = className,
        bounds = bounds,
        enabled = true,
        clickable = clickable,
        scrollable = scrollable,
        focused = false,
        packageName = packageName,
        password = password,
        clickableAncestor = clickableAncestor,
        parentId = parentId,
    )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // ---- envelopes ----

    @Test fun semanticJsonCarriesTheFullEnvelopeAndNodeList() {
        val text = UiObservationSerializer.semanticJson(
            UiObservation("com.example", listOf(node())),
            source = "uiautomator", observationId = "ui-1", revision = 1,
            elapsedMs = 42, truncated = false, stable = true,
        )
        assertEquals(
            """{"ok":true,"observationId":"ui-1","revision":1,"elapsedMs":42,"source":"uiautomator",""" +
                """"stable":true,"activePackage":"com.example","truncated":false,"nodes":""" +
                """[{"nodeId":"n0","text":"Send","resourceId":"com.example:id/send",""" +
                """"class":"android.widget.Button","bounds":[10,20,110,60],"enabled":true,""" +
                """"clickable":true,"scrollable":false,"focused":false}]}""",
            text,
        )
    }

    @Test fun semanticJsonOmitsActivePackageWhenUnknown() {
        val json = parse(
            UiObservationSerializer.semanticJson(
                UiObservation(null, listOf(node())), "uiautomator", "ui-1", 1, 0, false, true,
            )
        )
        assertFalse(json.containsKey("activePackage"))
        for (key in listOf("ok", "observationId", "revision", "elapsedMs", "source", "stable", "truncated", "nodes")) {
            assertTrue("missing $key", json.containsKey(key))
        }
    }

    @Test fun semanticJsonReportsTheBackendThatAnswered() {
        val json = parse(
            UiObservationSerializer.semanticJson(
                UiObservation("com.example", listOf(node())), "accessibility", "ui-1", 1, 0, false, false,
            )
        )
        assertEquals("accessibility", json["source"]!!.jsonPrimitive.content)
        assertFalse(json["stable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun semanticJsonKeepsTheClickableAncestorAsAPositionOnlyReference() {
        val ancestor = node(id = "n1", bounds = listOf(0, 0, 200, 100)).asClickTarget()
        val json = parse(
            UiObservationSerializer.semanticJson(
                UiObservation("com.example", listOf(node(clickableAncestor = ancestor))),
                "uiautomator", "ui-1", 1, 0, false, true,
            )
        )
        val emitted = json["nodes"]!!.jsonArray[0].jsonObject["clickableAncestor"]!!.jsonObject
        assertEquals("n1", emitted["nodeId"]!!.jsonPrimitive.content)
        assertEquals(listOf(0, 0, 200, 100), emitted["bounds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        // A parent reference carries position only; labels belong to the child.
        assertFalse(emitted.containsKey("text"))
        assertFalse(emitted.containsKey("resourceId"))
    }

    @Test fun unchangedJsonNamesTheEarlierRevisionAndExplainsWhatToDo() {
        val text = UiObservationSerializer.unchangedJson(
            activePackage = "com.example", nodeCount = 41, source = "uiautomator",
            observationId = "ui-13", revision = 13, elapsedMs = 7, unchangedSinceRevision = 12,
        )
        val json = parse(text)
        assertTrue(json["unchanged"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(12, json["unchangedSinceRevision"]!!.jsonPrimitive.content.toLong())
        assertEquals(41, json["nodeCount"]!!.jsonPrimitive.content.toInt())
        assertTrue(json["hint"]!!.jsonPrimitive.content.startsWith("Screen is identical to revision 12."))
        assertTrue(json["hint"]!!.jsonPrimitive.content.contains("force=true"))
        assertFalse(json.containsKey("nodes"))
    }

    @Test fun failureJsonOmitsTheOptionalFieldsWhenThereIsNothingToSay() {
        val json = parse(
            UiObservationSerializer.failureJson("ui-3", 3, 12, "ui_timeout", "Timed out")
        )
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("none", json["source"]!!.jsonPrimitive.content)
        assertEquals("ui_timeout", json["errorType"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("remedy"))
        assertFalse(json.containsKey("alternatives"))
        assertFalse(json.containsKey("reasons"))
    }

    @Test fun failureJsonCarriesRemedyAlternativesAndReasonsWhenSupplied() {
        val json = parse(
            UiObservationSerializer.failureJson(
                "-", 0, 0, "backend_unavailable", "No backend",
                remedy = "Enable the accessibility service.",
                alternatives = listOf("screenshot"),
                reasons = listOf("a11y_unavailable: off", "adb_unavailable: disconnected"),
            )
        )
        assertEquals("Enable the accessibility service.", json["remedy"]!!.jsonPrimitive.content)
        assertEquals(listOf("screenshot"), json["alternatives"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, json["reasons"]!!.jsonArray.size)
    }

    @Test fun failureMessageIsCappedSoAThrownStackNeverFloodsTheReply() {
        val json = parse(
            UiObservationSerializer.failureJson("-", 0, 0, "ui_dump_failure", "x".repeat(5_000))
        )
        assertEquals(
            UiObservationSerializer.MAX_UI_FIELD_CHARS,
            json["message"]!!.jsonPrimitive.content.length,
        )
    }

    // ---- digest ----

    @Test fun digestIsStableForIdenticalScreensAndMovesWithAnyRealChange() {
        val base = UiObservationSerializer.digest("com.example", listOf(node()))
        assertEquals(base, UiObservationSerializer.digest("com.example", listOf(node())))
        assertNotEquals(base, UiObservationSerializer.digest("com.example", listOf(node(bounds = listOf(10, 20, 110, 61)))))
        assertNotEquals(base, UiObservationSerializer.digest("com.other", listOf(node())))
        assertNotEquals(base, UiObservationSerializer.digest("com.example", emptyList()))
    }

    // ---- render ----

    @Test fun renderSuppressesAnIdenticalScreenAndForceOverridesIt() {
        val observation = UiObservation("com.example", listOf(node()))
        val first = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-1", 1, 0, previous = null, force = false, stable = true,
        )
        assertFalse(first.unchanged)
        val fingerprint = assertNotNull(first.fingerprint)

        val second = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-2", 2, 0, previous = fingerprint, force = false, stable = true,
        )
        assertTrue(second.unchanged)
        // Nothing new to remember: the model still holds revision 1.
        assertNull(second.fingerprint)
        assertTrue(second.text.contains("\"unchangedSinceRevision\":1"))

        val forced = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-3", 3, 0, previous = fingerprint, force = true, stable = true,
        )
        assertFalse(forced.unchanged)
        assertNotNull(forced.fingerprint)
        assertTrue(forced.text.contains("\"nodes\""))
    }

    @Test fun renderNeverSuppressesAcrossBackendsEvenWhenTheDigestMatches() {
        val observation = UiObservation("com.example", listOf(node()))
        val fromAdb = assertNotNull(
            UiObservationSerializer.render(
                observation, "uiautomator", "adb", "ui-1", 1, 0, null, force = false, stable = true,
            ).fingerprint
        )
        val fromA11y = UiObservationSerializer.render(
            observation, "accessibility", "a11y", "ui-2", 2, 0, previous = fromAdb, force = false, stable = true,
        )
        // Same digest, different backend: the node lists are not comparable, so
        // answering "unchanged" would point the model at a list it never saw.
        assertFalse(fromA11y.unchanged)
        assertNotNull(fromA11y.fingerprint)
    }

    @Test fun renderTruncatesAnOversizedScreenAndTerminates() {
        val nodes = (0 until 3_000).map { node(id = "n$it", text = "label ".repeat(20) + it) }
        val rendered = UiObservationSerializer.render(
            UiObservation("com.example", nodes), "uiautomator", "adb", "ui-1", 1, 0,
            previous = null, force = false, stable = true,
        )
        assertTrue(
            "output was ${rendered.text.length} chars",
            rendered.text.length <= UiObservationSerializer.MAX_OUTPUT_CHARS,
        )
        assertTrue(rendered.text.contains("\"truncated\":true"))
        assertFalse(rendered.unchanged)
    }

    @Test fun truncationDoesNotChangeTheDigestSoASuppressedScreenStaysComparable() {
        // The digest covers the whole screen, not the trimmed payload, so a
        // screen that truncates is still recognised as unchanged next time.
        val nodes = (0 until 3_000).map { node(id = "n$it", text = "label ".repeat(20) + it) }
        val observation = UiObservation("com.example", nodes)
        val rendered = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-1", 1, 0, null, force = false, stable = true,
        )
        assertEquals(
            UiObservationSerializer.digest("com.example", nodes),
            assertNotNull(rendered.fingerprint).digest,
        )
    }


    // ---- query and paging (issue #45) ----

    @Test fun queryReadsEveryFilterOffTheToolArguments() {
        val query = UiQuery.from(buildJsonObject {
            put("text", "  Amir  ")
            put("resourceId", "id/row")
            put("class", "TextView")
            put("package", "whatsapp")
            put("rootNodeId", "n7")
            put("clickableOnly", true)
            put("scrollableOnly", false)
            put("offset", 40)
            put("maxNodes", 25)
            put("maxChars", 8_000)
        })
        assertEquals("Amir", query.text)
        assertEquals("id/row", query.resourceId)
        assertEquals("TextView", query.className)
        assertEquals("whatsapp", query.packageName)
        assertEquals("n7", query.rootNodeId)
        assertTrue(query.clickableOnly)
        assertFalse(query.scrollableOnly)
        assertEquals(40, query.offset)
        assertEquals(25, query.maxNodes)
        assertFalse(query.isEmpty)
        assertTrue(UiQuery.from(buildJsonObject {}).isEmpty)
    }

    @Test fun queryClampsRatherThanRejectsOutOfRangeNumbers() {
        // A bad number should still return the screen. Failing the call would
        // make the model recover from its own typo instead of reading the UI.
        val query = UiQuery.from(buildJsonObject {
            put("offset", -5)
            put("maxNodes", 0)
            put("maxChars", 10)
        })
        assertEquals(0, query.offset)
        assertEquals(1, query.maxNodes)
        assertEquals(UiQuery.MIN_OUTPUT_CHARS, query.charBudget())
        assertEquals(
            UiObservationSerializer.MAX_OUTPUT_CHARS,
            UiQuery.from(buildJsonObject { put("maxChars", 10_000_000) }).charBudget(),
        )
        // Blank strings are absent, not a filter that matches everything.
        assertTrue(UiQuery.from(buildJsonObject { put("text", "   ") }).isEmpty)
    }

    @Test fun selectMatchesTextDescriptionResourceIdClassAndPackage() {
        val nodes = listOf(
            node(id = "n0", text = "Amir", resourceId = "com.whatsapp:id/row", packageName = "com.whatsapp"),
            node(id = "n1", text = "Bella", resourceId = "com.whatsapp:id/row", packageName = "com.whatsapp"),
            node(id = "n2", text = null, contentDescription = "Amir, 2 new messages", packageName = "com.whatsapp"),
            node(id = "n3", text = "Amir", packageName = "com.android.systemui"),
        )
        // Case-insensitive substring, over text or contentDescription.
        assertEquals(
            listOf("n0", "n2", "n3"),
            UiObservationSerializer.select(nodes, UiQuery(text = "amir")).map { it.nodeId },
        )
        assertEquals(
            listOf("n0", "n1", "n2"),
            UiObservationSerializer.select(nodes, UiQuery(packageName = "whatsapp")).map { it.nodeId },
        )
        assertEquals(
            listOf("n0", "n1"),
            UiObservationSerializer.select(nodes, UiQuery(resourceId = "id/row")).map { it.nodeId },
        )
        // Filters combine with AND.
        assertEquals(
            listOf("n0"),
            UiObservationSerializer.select(nodes, UiQuery(text = "amir", resourceId = "id/row"))
                .map { it.nodeId },
        )
        assertEquals(
            listOf("n0", "n1", "n2", "n3"),
            UiObservationSerializer.select(nodes, UiQuery.ALL).map { it.nodeId },
        )
    }

    @Test fun selectNeverMatchesTheHiddenTextOfAPasswordField() {
        // Matching the value would leak it one probe at a time, which is
        // exactly what never emitting it is there to prevent.
        val nodes = listOf(node(id = "n0", text = "hunter2", contentDescription = "Password", password = true))
        assertTrue(UiObservationSerializer.select(nodes, UiQuery(text = "hunter")).isEmpty())
        assertEquals(1, UiObservationSerializer.select(nodes, UiQuery(text = "password")).size)
    }

    @Test fun selectByRootNodeIdReturnsTheWholeSubtreeInTraversalOrder() {
        val nodes = listOf(
            node(id = "n0", text = "Header"),
            node(id = "n1", text = "List", scrollable = true),
            node(id = "n2", text = "Row A", parentId = "n1"),
            node(id = "n3", text = "Row A label", parentId = "n2"),
            node(id = "n4", text = "Row B", parentId = "n1"),
            node(id = "n5", text = "Footer"),
        )
        assertEquals(
            listOf("n1", "n2", "n3", "n4"),
            UiObservationSerializer.select(nodes, UiQuery(rootNodeId = "n1")).map { it.nodeId },
        )
        assertEquals(
            listOf("n2", "n3"),
            UiObservationSerializer.select(nodes, UiQuery(rootNodeId = "n2")).map { it.nodeId },
        )
        // A subtree still narrows further.
        assertEquals(
            listOf("n4"),
            UiObservationSerializer.select(nodes, UiQuery(rootNodeId = "n1", text = "Row B"))
                .map { it.nodeId },
        )
    }

    @Test fun renderRejectsARootNodeIdThatIsNotOnScreenInsteadOfAnsweringEmpty() {
        // An empty node list would read as "that part of the screen is empty",
        // which is a different and wrong answer.
        val rendered = UiObservationSerializer.render(
            UiObservation("com.example", listOf(node(id = "n0"))),
            "uiautomator", "adb", "ui-1", 1, 0, previous = null, force = false, stable = true,
            query = UiQuery(rootNodeId = "n99"),
        )
        assertFalse(rendered.ok)
        assertNull(rendered.fingerprint)
        val json = parse(rendered.text)
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("ui_unknown_node", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["remedy"]!!.jsonPrimitive.content.contains("without rootNodeId"))
    }

    @Test fun anOversizedScreenHandsBackACursorAndEveryNodeIsReachableThroughIt() {
        // Issue #45: the reply used to drop the tail of the list with nothing
        // but "truncated":true, so the omitted contacts were unreachable.
        val nodes = (0 until 3_000).map { node(id = "n$it", text = "label ".repeat(20) + it) }
        val observation = UiObservation("com.example", nodes)
        val seen = mutableListOf<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val rendered = UiObservationSerializer.render(
                observation, "uiautomator", "adb", "ui-$pages", pages.toLong(), 0,
                previous = null, force = false, stable = true, query = UiQuery(offset = offset),
            )
            assertTrue(rendered.text.length <= UiObservationSerializer.MAX_OUTPUT_CHARS)
            val json = parse(rendered.text)
            assertEquals(3_000, json["totalNodes"]!!.jsonPrimitive.content.toInt())
            seen += json["nodes"]!!.jsonArray.map { it.jsonObject["nodeId"]!!.jsonPrimitive.content }
            pages++
            assertTrue("paging must terminate", pages < 3_000)
            val next = json["nextOffset"] ?: break
            assertTrue(json["truncated"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(json["hint"]!!.jsonPrimitive.content.contains("offset="))
            offset = next.jsonPrimitive.content.toInt()
        }
        assertTrue("paging should take more than one call", pages > 1)
        // Every node the screen carried is retrievable, in order, exactly once.
        assertEquals(nodes.map { it.nodeId }, seen)
    }

    @Test fun aFilteredReplyReportsWhatItMatchedAndEchoesTheQuery() {
        val nodes = listOf(
            node(id = "n0", text = "Amir"),
            node(id = "n1", text = "Bella"),
            node(id = "n2", text = "Amira"),
        )
        val json = parse(
            UiObservationSerializer.render(
                UiObservation("com.example", nodes), "uiautomator", "adb", "ui-1", 1, 0,
                previous = null, force = false, stable = true, query = UiQuery(text = "amir"),
            ).text
        )
        assertEquals(3, json["totalNodes"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, json["matchedNodes"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, json["returnedNodes"]!!.jsonPrimitive.content.toInt())
        assertFalse(json["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("nothing left over, so no cursor", json.containsKey("nextOffset"))
        assertEquals("amir", json["query"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("n0", "n2"), json["nodes"]!!.jsonArray.map { it.jsonObject["nodeId"]!!.jsonPrimitive.content })
    }

    @Test fun aQueryThatMatchesNothingSaysSoInsteadOfLookingLikeAnEmptyScreen() {
        val json = parse(
            UiObservationSerializer.render(
                UiObservation("com.example", listOf(node(id = "n0", text = "Amir"))),
                "uiautomator", "adb", "ui-1", 1, 0,
                previous = null, force = false, stable = true, query = UiQuery(text = "zebra"),
            ).text
        )
        assertEquals(0, json["matchedNodes"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["totalNodes"]!!.jsonPrimitive.content.toInt())
        assertTrue(json["hint"]!!.jsonPrimitive.content.contains("No node matched"))
        assertTrue(json["nodes"]!!.jsonArray.isEmpty())
    }

    @Test fun maxNodesAndMaxCharsLowerTheCapsAndStillHandBackACursor() {
        val nodes = (0 until 20).map { node(id = "n$it", text = "row $it") }
        val observation = UiObservation("com.example", nodes)

        val capped = parse(
            UiObservationSerializer.render(
                observation, "uiautomator", "adb", "ui-1", 1, 0,
                previous = null, force = false, stable = true, query = UiQuery(maxNodes = 5),
            ).text
        )
        assertEquals(5, capped["returnedNodes"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, capped["nextOffset"]!!.jsonPrimitive.content.toInt())

        val narrow = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-2", 2, 0,
            previous = null, force = false, stable = true, query = UiQuery(maxChars = 1_500),
        )
        assertTrue("was ${narrow.text.length} chars", narrow.text.length <= 1_500)
        assertTrue(parse(narrow.text).containsKey("nextOffset"))
    }

    @Test fun offsetAdvancesThePageAndIsEchoedBack() {
        val nodes = (0 until 6).map { node(id = "n$it", text = "row $it") }
        val json = parse(
            UiObservationSerializer.render(
                UiObservation("com.example", nodes), "uiautomator", "adb", "ui-1", 1, 0,
                previous = null, force = false, stable = true, query = UiQuery(offset = 4),
            ).text
        )
        assertEquals(4, json["offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("n4", "n5"), json["nodes"]!!.jsonArray.map { it.jsonObject["nodeId"]!!.jsonPrimitive.content })
        assertFalse(json["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun anOffsetPastTheEndSaysSoRatherThanLookingLikeAnEmptyScreen() {
        val nodes = (0 until 6).map { node(id = "n$it", text = "row $it") }
        val json = parse(
            UiObservationSerializer.render(
                UiObservation("com.example", nodes), "uiautomator", "adb", "ui-1", 1, 0,
                previous = null, force = false, stable = true, query = UiQuery(offset = 99),
            ).text
        )
        assertEquals(0, json["returnedNodes"]!!.jsonPrimitive.content.toInt())
        assertTrue(json["hint"]!!.jsonPrimitive.content.contains("past the last of 6"))
    }

    @Test fun anUnfilteredReplyKeepsTheOldEnvelopeAndAddsOnlyTheCounts() {
        val json = parse(
            UiObservationSerializer.render(
                UiObservation("com.example", listOf(node())), "uiautomator", "adb", "ui-1", 1, 0,
                previous = null, force = false, stable = true,
            ).text
        )
        assertEquals(1, json["totalNodes"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["returnedNodes"]!!.jsonPrimitive.content.toInt())
        // Nothing was narrowed, so there is no query to echo and nothing to hint.
        assertFalse(json.containsKey("query"))
        assertFalse(json.containsKey("matchedNodes"))
        assertFalse(json.containsKey("offset"))
        assertFalse(json.containsKey("hint"))
    }

    @Test fun aDifferentQueryOnTheSameScreenIsNeverSuppressedAsUnchanged() {
        // The screen is byte-identical, but the question asked of it is not,
        // so "reuse the nodes from revision N" would answer the wrong one.
        val observation = UiObservation("com.example", listOf(node(id = "n0", text = "Amir")))
        val first = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-1", 1, 0,
            previous = null, force = false, stable = true, query = UiQuery(text = "amir"),
        )
        val other = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-2", 2, 0,
            previous = first.fingerprint, force = false, stable = true, query = UiQuery(offset = 0, text = "bella"),
        )
        assertFalse(other.unchanged)

        val repeated = UiObservationSerializer.render(
            observation, "uiautomator", "adb", "ui-3", 3, 0,
            previous = first.fingerprint, force = false, stable = true, query = UiQuery(text = "amir"),
        )
        assertTrue("the same screen and the same query is still unchanged", repeated.unchanged)
    }

    // ---- privacy ----

    @Test fun passwordNodesNeverEmitTheirText() {
        val json = parse(
            UiObservationSerializer.semanticJson(
                UiObservation("com.example", listOf(node(text = "hunter2", password = true))),
                "uiautomator", "ui-1", 1, 0, false, true,
            )
        )
        val emitted = json["nodes"]!!.jsonArray[0].jsonObject
        assertTrue(emitted["password"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(emitted.containsKey("text"))
    }

    @Test fun onScreenCredentialsAreRedactedBeforeTheyLeaveTheDevice() {
        val json = parse(
            UiObservationSerializer.semanticJson(
                UiObservation(
                    "com.example",
                    listOf(node(text = "key sk-abcdefgh1234", contentDescription = "Bearer abcdefgh1234")),
                ),
                "uiautomator", "ui-1", 1, 0, false, true,
            )
        )
        val emitted = json["nodes"]!!.jsonArray[0].jsonObject
        assertEquals("key [REDACTED_API_KEY]", emitted["text"]!!.jsonPrimitive.content)
        assertEquals("Bearer [REDACTED]", emitted["contentDescription"]!!.jsonPrimitive.content)
    }

    @Test fun ordinaryScreenTextWithAQuestionMarkSurvivesRedaction() {
        // SecretRedactor.redact rewrites everything after a "?", which is right
        // for a URL in a log line and wrong for a dialog on screen.
        val json = parse(
            UiObservationSerializer.semanticJson(
                UiObservation("com.example", listOf(node(text = "Delete this chat?Undo is not possible"))),
                "uiautomator", "ui-1", 1, 0, false, true,
            )
        )
        assertEquals(
            "Delete this chat?Undo is not possible",
            json["nodes"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    // ---- field handling ----

    @Test fun compactFieldTrimsDropsBlanksAndCaps() {
        assertEquals("Send", UiObservationSerializer.compactField("  Send  "))
        assertNull(UiObservationSerializer.compactField("   "))
        assertNull(UiObservationSerializer.compactField(null))
        assertEquals(
            UiObservationSerializer.MAX_UI_FIELD_CHARS,
            UiObservationSerializer.compactField("x".repeat(1_000))!!.length,
        )
    }

    // ---- shared state ----

    @Test fun observationStateHandsOutIncreasingRevisionsAndForgetsOnReset() {
        val state = ObservationState()
        val first = state.nextRevision()
        val second = state.nextRevision()
        assertTrue(second > first)
        assertNull(state.last())

        val fingerprint = ObservationFingerprint("digest", second, "adb")
        state.record(fingerprint)
        assertEquals(fingerprint, state.last())

        state.reset()
        assertNull(state.last())
        // Revisions keep climbing across a reset, so an id is never reused.
        assertTrue(state.nextRevision() > second)
    }

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull("expected a value", value)
        return value!!
    }
}
