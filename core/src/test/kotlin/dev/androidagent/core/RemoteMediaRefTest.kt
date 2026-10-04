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
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaRefTest {
    @Test fun aWindowsPathWithColonsAndSpacesSurvivesTheRoundTrip() {
        val ref = RemoteMediaRef("Home PC: main", "C:\\Users\\me\\My Videos\\clip 1.mp4", 1_234_567)
        val text = ref.encode()
        assertTrue(RemoteMediaRef.isRemote(text))
        assertEquals(ref, RemoteMediaRef.parse(text))
        assertEquals("clip 1.mp4", ref.name)
    }

    @Test fun aLinuxPathAndAnEmptyFileParse() {
        assertEquals(RemoteMediaRef("Server", "/home/me/a.png", 0), RemoteMediaRef.parse(RemoteMediaRef("Server", "/home/me/a.png", 0).encode()))
    }

    @Test fun aLocalPathOrAMalformedReferenceIsNotOne() {
        assertNull(RemoteMediaRef.parse("/data/user/0/app/media/a.png"))
        assertFalse(RemoteMediaRef.isRemote("C:\\a.png"))
        for (bad in listOf("remote:", "remote:Pc", "remote:Pc:12", "remote:Pc:x:/a.png", "remote:Pc:-1:/a.png", "remote::5:/a.png", "remote:Pc:5:")) {
            assertNull(bad, RemoteMediaRef.parse(bad))
        }
    }

    @Test fun theKindComesFromTheNameAndPicksWhereSaveGoes() {
        assertEquals("Pictures/Hey Mike/shot.PNG", RemoteMediaRef("Pc", "/x/shot.PNG", 1).savePath)
        assertEquals("Movies/Hey Mike/demo.mp4", RemoteMediaRef("Pc", "C:\\x\\demo.mp4", 1).savePath)
        assertEquals("Download/notes.txt", RemoteMediaRef("Pc", "/x/notes.txt", 1).savePath)
        // Nothing in the name can climb out of the folder.
        assertEquals("Pictures/Hey Mike/a_b.png", RemoteMediaRef("Pc", "/x/a:b.png", 1).savePath)
    }

    @Test fun anAttachmentIsRecognisedAsMediaByItsName() {
        val remote = RemoteMediaRef("Pc", "C:\\v\\demo.mp4", 9).encode()
        assertTrue(ChatTools.isVideo(remote))
        assertTrue(ChatTools.isMedia(remote))
        assertEquals("demo.mp4", ChatTools.displayName(remote))
        assertEquals("a.png", ChatTools.displayName("/data/media/a.png"))
    }
}
