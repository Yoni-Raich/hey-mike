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
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalReplyTest {

    @Test fun plainYesInHebrewAndEnglishAllows() {
        for (reply in listOf("כן", "כן!", "אשר", "תאשר", "מאשר", "שלח", "yes", "Yes.", "OK", "go ahead", "Send it")) {
            assertEquals(reply, true, ApprovalReply.parse(reply))
        }
    }

    @Test fun plainNoInHebrewAndEnglishDenies() {
        for (reply in listOf("לא", "לא.", "בטל", "אל תשלח", "no", "No!", "cancel", "don't send", "deny")) {
            assertEquals(reply, false, ApprovalReply.parse(reply))
        }
    }

    @Test fun aWakeWordRepetitionAndPolitenessStillCount() {
        assertEquals(true, ApprovalReply.parse("מייק, כן בבקשה"))
        assertEquals(true, ApprovalReply.parse("Hey Mike, yes please"))
        assertEquals(true, ApprovalReply.parse("כן כן"))
        assertEquals(false, ApprovalReply.parse("לא, תודה"))
    }

    @Test fun anInstructionIsNeverReadAsAnAnswer() {
        // These carry more than a yes or no, so the agent must see them.
        for (reply in listOf("כן אבל תשנה את ההודעה", "yes but at 7pm", "send it to mom instead", "", "   ", "מה?", "stop")) {
            assertNull(reply, ApprovalReply.parse(reply))
        }
    }
}
