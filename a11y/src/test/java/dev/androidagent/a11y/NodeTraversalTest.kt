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

package dev.androidagent.a11y

import dev.androidagent.core.UiObservationSerializer
import dev.androidagent.core.UiQuery
import org.junit.Assert.*
import org.junit.Test

class NodeTraversalTest {

    private companion object {
        const val OWN = "dev.androidagent.app.dev"
        const val APP = "com.whatsapp"
    }

    private class FakeNode(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val viewIdResourceName: String? = null,
        override val className: String? = "android.view.View",
        override val packageName: String? = APP,
        override val boundsInScreen: List<Int> = listOf(0, 0, 100, 100),
        override val isEnabled: Boolean = true,
        override val isClickable: Boolean = false,
        override val isScrollable: Boolean = false,
        override val isFocused: Boolean = false,
        override val isVisibleToUser: Boolean = true,
        override val isPassword: Boolean = false,
        override val isEditable: Boolean = false,
        override val isSelected: Boolean = false,
        override val rangeMin: Float? = null,
        override val rangeMax: Float? = null,
        override val rangeCurrent: Float? = null,
        override val rangeType: String? = null,
        override val supportsSetProgress: Boolean = false,
        override val isCheckable: Boolean = false,
        override val isChecked: Boolean = false,
        val children: List<A11yNodeView> = emptyList(),
    ) : A11yNodeView {
        override val childCount: Int get() = children.size
        override fun child(index: Int): A11yNodeView? = children.getOrNull(index)
    }

    private fun window(root: A11yNodeView?, active: Boolean = true) = A11yWindow(root, active)

    @Test fun eachNodeNamesTheNearestAncestorThatWasItselfEmitted() {
        // rootNodeId resolves a subtree out of the flat list, so a node that
        // was dropped must not break the chain: its children adopt the nearest
        // ancestor that survived.
        val tree = FakeNode(
            text = "Screen",
            children = listOf(
                FakeNode(
                    text = "List",
                    isScrollable = true,
                    children = listOf(
                        // Unlabelled and uninteractive: dropped from the list.
                        FakeNode(children = listOf(FakeNode(text = "Row A"))),
                        FakeNode(text = "Row B"),
                    ),
                ),
            ),
        )
        val nodes = traverse(listOf(window(tree)), OWN).observation.nodes
        val byText = nodes.associateBy { it.text }
        assertNull(byText.getValue("Screen").parentId)
        assertEquals(byText.getValue("Screen").nodeId, byText.getValue("List").parentId)
        // The dropped wrapper is skipped over, not treated as a parent.
        assertEquals(byText.getValue("List").nodeId, byText.getValue("Row A").parentId)
        assertEquals(byText.getValue("List").nodeId, byText.getValue("Row B").parentId)
    }

    @Test fun aSubtreeQueryOverARealTraversalReturnsOnlyThatBranch() {
        val tree = FakeNode(
            text = "Screen",
            children = listOf(
                FakeNode(text = "Chats", children = listOf(FakeNode(text = "Amir"), FakeNode(text = "Bella"))),
                FakeNode(text = "Tabs", children = listOf(FakeNode(text = "Calls"))),
            ),
        )
        val nodes = traverse(listOf(window(tree)), OWN).observation.nodes
        val chats = nodes.first { it.text == "Chats" }
        assertEquals(
            listOf("Chats", "Amir", "Bella"),
            UiObservationSerializer.select(nodes, UiQuery(rootNodeId = chats.nodeId)).map { it.text },
        )
    }

    @Test fun visibleLabelledNodesAreEmittedInPreorder() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(text = "First"),
                FakeNode(
                    text = "Second",
                    children = listOf(FakeNode(text = "Third")),
                ),
                FakeNode(text = "Fourth"),
            ),
        )
        val result = traverse(listOf(window(tree)), OWN)
        assertEquals(
            listOf("First", "Second", "Third", "Fourth"),
            result.observation.nodes.map { it.text },
        )
    }

    @Test fun unlabelledAndUninteractiveNodesAreDropped() {
        val tree = FakeNode(children = listOf(FakeNode(), FakeNode(text = "Send")))
        val result = traverse(listOf(window(tree)), OWN)
        assertEquals(listOf("Send"), result.observation.nodes.map { it.text })
    }

    @Test fun aDisabledOrClickableNodeIsKeptEvenWithoutALabel() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(isClickable = true),
                FakeNode(isEnabled = false),
                FakeNode(isScrollable = true),
            ),
        )
        assertEquals(3, traverse(listOf(window(tree)), OWN).observation.nodes.size)
    }

    @Test fun ourOwnWindowIsNeverTraversed() {
        // The floating card stays in the window manager even when it is hidden
        // from screenshots, so this filter is what keeps the agent off its own UI.
        val ours = FakeNode(packageName = OWN, text = "Stop", isClickable = true)
        val theirs = FakeNode(packageName = APP, text = "Send", isClickable = true)
        val result = traverse(listOf(window(ours), window(theirs, active = false)), OWN)
        assertEquals(listOf("Send"), result.observation.nodes.map { it.text })
    }

    @Test fun ourOwnNodesAreDroppedEvenInsideSomeoneElsesWindow() {
        val tree = FakeNode(
            packageName = APP,
            children = listOf(
                FakeNode(packageName = OWN, text = "Stop", isClickable = true),
                FakeNode(packageName = APP, text = "Send", isClickable = true),
            ),
        )
        val result = traverse(listOf(window(tree)), OWN)
        assertEquals(listOf("Send"), result.observation.nodes.map { it.text })
    }

    @Test fun invisibleNodesAreDropped() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(text = "Hidden", isVisibleToUser = false),
                FakeNode(text = "Shown"),
            ),
        )
        val result = traverse(listOf(window(tree)), OWN)
        assertEquals(listOf("Shown"), result.observation.nodes.map { it.text })
    }

    @Test fun anInvisibleSubtreeIsNotDescendedInto() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(
                    text = "Collapsed",
                    isVisibleToUser = false,
                    children = listOf(FakeNode(text = "Buried")),
                ),
            ),
        )
        assertTrue(traverse(listOf(window(tree)), OWN).observation.nodes.isEmpty())
    }

    @Test fun aLabelledChildCarriesItsNearestClickableAncestor() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(
                    isClickable = true,
                    boundsInScreen = listOf(0, 0, 500, 200),
                    children = listOf(FakeNode(text = "Danny", boundsInScreen = listOf(20, 40, 300, 90))),
                ),
            ),
        )
        val result = traverse(listOf(window(tree)), OWN)
        val label = result.observation.nodes.first { it.text == "Danny" }
        val ancestor = assertNotNull(label.clickableAncestor)
        assertEquals(listOf(0, 0, 500, 200), ancestor.bounds)
        // The parent reference carries position only.
        assertNull(ancestor.text)
        assertNull(ancestor.resourceId)
    }

    @Test fun theNearestClickableAncestorWinsOverAFartherOne() {
        val tree = FakeNode(
            isClickable = true,
            boundsInScreen = listOf(0, 0, 1000, 1000),
            children = listOf(
                FakeNode(
                    isClickable = true,
                    boundsInScreen = listOf(0, 0, 500, 200),
                    children = listOf(FakeNode(text = "Danny")),
                ),
            ),
        )
        val label = traverse(listOf(window(tree)), OWN).observation.nodes.first { it.text == "Danny" }
        assertEquals(listOf(0, 0, 500, 200), assertNotNull(label.clickableAncestor).bounds)
    }

    @Test fun aClickableNodeIsItsOwnTargetAndCarriesNoAncestor() {
        val tree = FakeNode(children = listOf(FakeNode(text = "Send", isClickable = true)))
        val node = traverse(listOf(window(tree)), OWN).observation.nodes.single()
        assertTrue(node.clickable)
        assertNull(node.clickableAncestor)
    }

    @Test fun everyEmittedNodeHasAHandleAndIdsAreUnique() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(),
                FakeNode(text = "One"),
                FakeNode(),
                FakeNode(text = "Two"),
            ),
        )
        val result = traverse(listOf(window(tree)), OWN)
        val ids = result.observation.nodes.map { it.nodeId }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.toSet(), result.handles.keys)
    }

    @Test fun anAncestorIdNamesTheNodeItWasTakenFrom() {
        // Ids come from a counter over every visited node, so a dropped node
        // can never let two different nodes share one.
        val tree = FakeNode(
            children = listOf(
                FakeNode(
                    isClickable = true,
                    children = listOf(FakeNode(), FakeNode(text = "Danny")),
                ),
            ),
        )
        val nodes = traverse(listOf(window(tree)), OWN).observation.nodes
        val parent = nodes.first { it.clickable }
        val label = nodes.first { it.text == "Danny" }
        assertEquals(parent.nodeId, assertNotNull(label.clickableAncestor).nodeId)
        assertNotEquals(parent.nodeId, label.nodeId)
    }

    @Test fun activePackageComesFromTheFocusedWindow() {
        val background = FakeNode(packageName = "com.android.launcher", text = "Home")
        val foreground = FakeNode(packageName = APP, text = "Send")
        val result = traverse(
            listOf(window(background, active = false), window(foreground, active = true)),
            OWN,
        )
        assertEquals(APP, result.observation.activePackage)
    }

    @Test fun activePackageFallsBackToTheCommonestWhenNoWindowIsActive() {
        val tree = FakeNode(
            packageName = APP,
            children = listOf(FakeNode(text = "One"), FakeNode(text = "Two")),
        )
        val result = traverse(listOf(window(tree, active = false)), OWN)
        assertEquals(APP, result.observation.activePackage)
    }

    @Test fun aNullWindowRootIsSkippedRatherThanFailing() {
        val result = traverse(
            listOf(window(null), window(FakeNode(text = "Send"))),
            OWN,
        )
        assertEquals(listOf("Send"), result.observation.nodes.map { it.text })
    }

    @Test fun aPasswordFieldIsMarkedSoItsTextIsNeverEmitted() {
        val tree = FakeNode(children = listOf(FakeNode(text = "hunter2", isPassword = true, isEditable = true)))
        val node = traverse(listOf(window(tree)), OWN).observation.nodes.single()
        assertTrue(node.password)
        assertFalse(node.toJson().containsKey("text"))
    }

    @Test fun aSwitchCarriesItsOnOffStateAndAPlainLabelCarriesNone() {
        // "the switch is off" and "this is not a switch" are different answers
        // to "did the toggle take effect", so only a checkable node reports one.
        val tree = FakeNode(
            children = listOf(
                FakeNode(text = "Wireless debugging", isCheckable = true, isChecked = true, isClickable = true),
                FakeNode(text = "About phone", isClickable = true),
            ),
        )
        val nodes = traverse(listOf(window(tree)), OWN).observation.nodes
        val switch = nodes.single { it.text == "Wireless debugging" }
        assertTrue(switch.checkable)
        assertTrue(switch.checked)
        assertTrue(switch.toJson()["checked"].toString().toBoolean())
        assertFalse(nodes.single { it.text == "About phone" }.toJson().containsKey("checked"))
    }

    @Test fun anUnlabelledSwitchIsStillEmittedBecauseItsStateIsTheAnswer() {
        val tree = FakeNode(children = listOf(FakeNode(isCheckable = true, isChecked = false)))
        val node = traverse(listOf(window(tree)), OWN).observation.nodes.single()
        assertTrue(node.checkable)
        assertFalse(node.checked)
    }

    @Test fun aSeekBarCarriesSemanticRangeAndSetProgressAction() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(
                    text = "Volume Slider: 20%",
                    className = "android.widget.SeekBar",
                    rangeMin = 0f,
                    rangeMax = 100f,
                    rangeCurrent = 20f,
                    rangeType = "int",
                    supportsSetProgress = true,
                ),
            ),
        )
        val node = traverse(listOf(window(tree)), OWN).observation.nodes.single()
        assertEquals(0.0, node.range!!.min, 0.0)
        assertEquals(100.0, node.range!!.max, 0.0)
        assertEquals(20.0, node.range!!.current, 0.0)
        assertEquals("int", node.range!!.type)
        assertTrue(node.supportsSetProgress)
        assertTrue(node.toJson()["actions"].toString().contains("SET_PROGRESS"))
    }

    @Test fun aRunawayTreeIsBoundedRatherThanExhaustingTheHeap() {
        // A very wide tree must cost the node budget and terminate.
        val wide = FakeNode(children = (0 until 8_000).map { FakeNode(text = "row $it") })
        val result = traverse(listOf(window(wide)), OWN)
        assertTrue(result.observation.nodes.size <= UiObservationSerializer.MAX_UI_NODES)
        assertTrue(result.observation.nodes.isNotEmpty())
    }

    @Test fun aDeeplyNestedTreeStopsAtTheDepthCeiling() {
        var node: A11yNodeView = FakeNode(text = "deepest")
        repeat(400) { node = FakeNode(children = listOf(node)) }
        val result = traverse(listOf(window(node)), OWN)
        // It terminates without recursing, and never reaches the buried label.
        assertTrue(result.observation.nodes.none { it.text == "deepest" })
    }

    @Test fun fieldsAreTrimmedAndCappedTheSameWayTheAdbBackendTrimsThem() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(text = "  Send  ", contentDescription = "   ", viewIdResourceName = "x".repeat(1_000)),
            ),
        )
        val node = traverse(listOf(window(tree)), OWN).observation.nodes.single()
        assertEquals("Send", node.text)
        assertNull(node.contentDescription)
        assertEquals(UiObservationSerializer.MAX_UI_FIELD_CHARS, node.resourceId!!.length)
    }

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull("expected a value", value)
        return value!!
    }
}
