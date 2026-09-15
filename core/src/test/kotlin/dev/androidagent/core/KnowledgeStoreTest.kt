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

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KnowledgeStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private var clock = 1_000_000L

    private fun store() = KnowledgeStore(temp.root) { clock }

    private fun record(
        pkg: String = "com.whatsapp",
        selector: String = "com.whatsapp:id/search",
        does: String = "opens search",
    ) = KnowledgeStore.Record(
        packageName = pkg,
        screen = "chat list",
        selector = selector,
        does = does,
    )

    @Test fun aRecordSurvivesIntoAFreshStore() {
        // The whole point: a different chat is a different object over the
        // same directory, and must see what the first one learned.
        store().upsert(record())
        val recalled = KnowledgeStore(temp.root) { clock }.read("com.whatsapp")
        assertEquals(1, recalled.size)
        assertEquals("com.whatsapp:id/search", recalled.single().selector)
        assertEquals("opens search", recalled.single().does)
    }

    @Test fun upsertStampsTheVerificationTime() {
        val stored = store().upsert(record())
        assertEquals(clock, stored.lastVerified)
    }

    @Test fun relearningTheSameSelectorReplacesRatherThanDuplicates() {
        val subject = store()
        subject.upsert(record(does = "opens search"))
        clock += 5_000
        subject.upsert(record(does = "opens search and focuses the field"))
        val all = subject.read("com.whatsapp")
        assertEquals(1, all.size)
        assertEquals("opens search and focuses the field", all.single().does)
        assertEquals(clock, all.single().lastVerified)
    }

    @Test fun differentSelectorsCoexist() {
        val subject = store()
        subject.upsert(record(selector = "com.whatsapp:id/search"))
        subject.upsert(record(selector = "com.whatsapp:id/send"))
        assertEquals(2, subject.read("com.whatsapp").size)
    }

    @Test fun recordsComeBackNewestVerifiedFirst() {
        val subject = store()
        subject.upsert(record(selector = "a"))
        clock += 10_000
        subject.upsert(record(selector = "b"))
        assertEquals(listOf("b", "a"), subject.read("com.whatsapp").map { it.selector })
    }

    @Test fun packagesAreIsolatedFromEachOther() {
        val subject = store()
        subject.upsert(record(pkg = "com.whatsapp", selector = "a"))
        subject.upsert(record(pkg = "com.android.chrome", selector = "b"))
        assertEquals(listOf("a"), subject.read("com.whatsapp").map { it.selector })
        assertEquals(listOf("b"), subject.read("com.android.chrome").map { it.selector })
        assertEquals(listOf("com.android.chrome", "com.whatsapp"), subject.packages())
    }

    @Test fun aBareCoordinateIsRefusedAsASelector() {
        // Storing one would quietly undo the reason this store exists: it stops
        // being true on the next render.
        for (bad in listOf("540,1200", "[540,1200]", "(540, 1200)", "540;1200", " 540 , 1200 ")) {
            assertThrows(IllegalArgumentException::class.java) {
                store().upsert(record(selector = bad))
            }
        }
    }

    @Test fun anOrdinaryLabelThatContainsDigitsIsNotACoordinate() {
        // Regression: the first version of the check matched a digit pair
        // anywhere in the selector, so real contentDescriptions were refused.
        val labels = listOf(
            "1,234 messages",
            "3,000 photos",
            "12,5 km to destination",
            "Chat with Danny, 2 unread",
            "Row 4, column 2 of the grid",
        )
        for (label in labels) {
            val subject = store()
            clock += 1
            subject.upsert(record(selector = label))
            assertTrue("$label should be storable", subject.read("com.whatsapp").any { it.selector == label })
        }
    }

    @Test fun aScreenWithNothingAddressableCanStillBeRecorded() {
        // A canvas, a game, an unexposed WebView. Refusing the record outright
        // would lose the partial knowledge as well as the coordinate.
        val subject = store()
        subject.upsert(
            record(selector = "game canvas: fire button").copy(hint = "roughly 980,1840 on a 1080x2400 screen"),
        )
        val back = subject.read("com.whatsapp").single()
        assertEquals("roughly 980,1840 on a 1080x2400 screen", back.hint)
    }

    @Test fun theHintReachesTheSummarySoItIsUsableNotJustStored() {
        val subject = store()
        subject.upsert(record().copy(hint = "bottom right, below the composer"))
        val entry = subject.summary("com.whatsapp")["records"]!!.jsonArray.single().jsonObject
        assertEquals("bottom right, below the composer", entry["hint"]!!.jsonPrimitive.content)
    }

    @Test fun aBlankSelectorIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            store().upsert(record(selector = "   "))
        }
    }

    @Test fun anInvalidPackageNameIsRefusedRatherThanSanitised() {
        // A sanitised path is a path someone later mistakes for the real
        // package name, so traversal attempts are rejected outright.
        for (bad in listOf("../../etc/passwd", "notapackage", "com/whatsapp", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                store().upsert(record(pkg = bad))
            }
        }
    }

    @Test fun readingAnUnknownPackageIsEmptyRatherThanAnError() {
        assertTrue(store().read("com.example.absent").isEmpty())
    }

    @Test fun aCorruptFileIsIgnoredRatherThanCrashing() {
        temp.newFile("com.whatsapp.json").writeText("{ this is not json")
        assertTrue(store().read("com.whatsapp").isEmpty())
    }

    @Test fun aRecordAgesIntoStaleness() {
        val subject = store()
        val stored = subject.upsert(record())
        assertFalse(subject.isStale(stored))
        clock += KnowledgeStore.STALE_AFTER_MS + 1
        assertTrue(subject.isStale(stored))
    }

    @Test fun theSummaryMarksStalenessSoTheModelKnowsWhatToTrust() {
        val subject = store()
        subject.upsert(record())
        clock += KnowledgeStore.STALE_AFTER_MS + 1
        val summary = subject.summary("com.whatsapp")
        val entry = summary["records"]!!.jsonArray.single().jsonObject
        assertTrue(entry["stale"]!!.jsonPrimitive.booleanOrNull == true)
        assertTrue(summary["guidance"]!!.jsonPrimitive.content.contains("verify", ignoreCase = true))
    }

    @Test fun anEmptySummaryTellsTheModelToRecordWhatItLearns() {
        val summary = store().summary("com.example.absent")
        assertEquals(0, summary["known"]!!.jsonPrimitive.intOrNull)
        assertTrue(summary["guidance"]!!.jsonPrimitive.content.contains("remember_capability"))
    }

    @Test fun theSummaryIsBoundedSoItCannotFloodThePrompt() {
        val subject = store()
        repeat(40) { index ->
            clock += 1
            subject.upsert(record(selector = "com.whatsapp:id/row$index"))
        }
        assertEquals(5, subject.summary("com.whatsapp", limit = 5)["records"]!!.jsonArray.size)
        assertEquals(
            KnowledgeStore.DEFAULT_SUMMARY_LIMIT,
            subject.summary("com.whatsapp")["records"]!!.jsonArray.size,
        )
    }

    @Test fun theStoreIsCappedPerPackage() {
        val subject = store()
        repeat(KnowledgeStore.MAX_RECORDS_PER_PACKAGE + 20) { index ->
            clock += 1
            subject.upsert(record(selector = "id$index"))
        }
        assertEquals(KnowledgeStore.MAX_RECORDS_PER_PACKAGE, subject.read("com.whatsapp").size)
        // The cap drops the oldest, so the most recently verified survives.
        assertTrue(subject.read("com.whatsapp").any { it.selector == "id${KnowledgeStore.MAX_RECORDS_PER_PACKAGE + 19}" })
    }

    @Test fun fallbacksAndIntentSurviveARoundTrip() {
        store().upsert(
            record().copy(intent = "https://wa.me/1", fallbacks = listOf("Search", "magnifier icon")),
        )
        val back = store().read("com.whatsapp").single()
        assertEquals("https://wa.me/1", back.intent)
        assertEquals(listOf("Search", "magnifier icon"), back.fallbacks)
    }

    @Test fun aCorruptExistingFileIsNeverOverwrittenByTheNextUpsert() {
        val file = File(temp.root, "com.whatsapp.json")
        file.writeText("{broken")

        assertThrows(Exception::class.java) {
            store().upsert(record())
        }
        assertEquals("{broken", file.readText())
    }

    @Test fun anOversizedFallbackIsRefusedBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            store().upsert(record().copy(fallbacks = listOf("x".repeat(401))))
        }
    }
}
