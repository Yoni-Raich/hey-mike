package dev.androidagent.a11y

import org.junit.Assert.*
import org.junit.Test

class PairingScanTest {

    private class FakeNode(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val viewIdResourceName: String? = null,
        override val className: String? = "android.view.View",
        override val packageName: String? = "com.android.settings",
        override val boundsInScreen: List<Int> = listOf(0, 0, 100, 100),
        override val isEnabled: Boolean = true,
        override val isClickable: Boolean = false,
        override val isScrollable: Boolean = false,
        override val isFocused: Boolean = false,
        override val isVisibleToUser: Boolean = true,
        override val isPassword: Boolean = false,
        override val isEditable: Boolean = false,
        override val isCheckable: Boolean = false,
        override val isChecked: Boolean = false,
        val children: List<A11yNodeView> = emptyList(),
    ) : A11yNodeView {
        override val childCount: Int get() = children.size
        override fun child(index: Int): A11yNodeView? = children.getOrNull(index)
    }

    @Test fun aRealPairingDialogYieldsBothValues() {
        val details = PairingScan.parse(
            listOf(
                "Pair with device",
                "Wi-Fi pairing code",
                "418302",
                "IP address & Port",
                "192.168.1.24:37199",
                "Cancel",
            ),
        )
        assertEquals(PairingDetails("418302", 37199), details)
    }

    @Test fun theAddressIsNeverMistakenForTheCode() {
        // Digits from the address must not be read as a pairing code, even
        // when the code has not appeared on screen yet.
        assertNull(PairingScan.parse(listOf("IP address & Port", "192.168.100.101:41231")))
    }

    @Test fun aHalfDrawnDialogYieldsNothing() {
        assertNull(PairingScan.parse(listOf("Pair with device", "418302")))
        assertNull(PairingScan.parse(emptyList()))
    }

    @Test fun aPortOutsideTheAdbRangeIsRefused() {
        assertNull(PairingScan.parse(listOf("418302", "192.168.1.24:80")))
    }

    @Test fun onlySettingsWindowsAreRead() {
        // The scan runs while the user is out of the app, so anything that is
        // not the settings app is none of its business.
        val settings = A11yWindow(
            FakeNode(children = listOf(FakeNode(text = "418302"), FakeNode(text = "192.168.1.24:37199"))),
            active = true,
        )
        val other = A11yWindow(
            FakeNode(packageName = "com.whatsapp", children = listOf(FakeNode(text = "secret", packageName = "com.whatsapp"))),
            active = false,
        )
        val texts = PairingScan.collectText(listOf(settings, other))
        assertTrue(texts.contains("418302"))
        assertFalse(texts.any { it.contains("secret") })
        assertEquals(PairingDetails("418302", 37199), PairingScan.parse(texts))
    }

    @Test fun contentDescriptionsCount() {
        val window = A11yWindow(
            FakeNode(children = listOf(FakeNode(contentDescription = "Pairing code 418302"), FakeNode(text = "10.0.0.5:44444"))),
            active = true,
        )
        assertEquals(PairingDetails("418302", 44444), PairingScan.parse(PairingScan.collectText(listOf(window))))
    }

    @Test fun aDeepTreeCannotStallTheScan() {
        var node: A11yNodeView = FakeNode(text = "leaf")
        repeat(500) { node = FakeNode(text = "level", children = listOf(node)) }
        val texts = PairingScan.collectText(listOf(A11yWindow(node, active = true)), limit = 50)
        assertEquals(50, texts.size)
    }
}
