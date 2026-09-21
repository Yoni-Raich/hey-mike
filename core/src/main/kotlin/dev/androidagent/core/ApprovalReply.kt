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

import java.util.Locale

/**
 * Reads a spoken or typed reply to a waiting approval.
 *
 * An approval used to be answerable only by tapping a card inside the app,
 * which a user in voice mode, or looking at another app, cannot reach. A short
 * "yes" / "כן" or "no" / "לא" now answers it too.
 *
 * Deliberately strict: the whole reply must be one of the phrases below, with
 * at most a wake word ("Mike", "מייק") and a politeness word around it.
 * "Yes, but change it to 7pm" is an instruction, not a yes, and is left for
 * the agent. Only the user's own words reach this: typed text, and voice
 * transcripts with the user role. The model's output never does.
 */
object ApprovalReply {

    private val YES = setOf(
        "yes", "yeah", "yep", "ok", "okay", "sure", "allow", "approve", "approved", "confirm", "confirmed",
        "send", "send it", "go", "go ahead", "do it",
        "כן", "אשר", "אשרי", "תאשר", "תאשרי", "מאשר", "מאשרת", "אישור", "מאושר", "בסדר", "אוקיי", "יאללה",
        "שלח", "שלחי", "תשלח", "תשלחי", "קדימה", "סבבה",
    )

    private val NO = setOf(
        "no", "nope", "deny", "denied", "cancel", "reject", "dont", "do not", "dont send", "do not send",
        "לא", "בטל", "תבטל", "בטלי", "תבטלי", "דחה", "תדחה", "אל תשלח", "אל תשלחי", "עזוב", "לא לשלוח",
    )

    private val WAKE = setOf("hey mike", "mike", "היי מייק", "מייק")
    private val POLITE = setOf("please", "thanks", "thank you", "בבקשה", "תודה")

    /** true to allow, false to deny, null when the reply is not a plain yes or no. */
    fun parse(text: String): Boolean? {
        var reply = normalize(text)
        if (reply.isEmpty()) return null
        WAKE.firstOrNull { reply.startsWith("$it ") }?.let { reply = reply.removePrefix("$it ") }
        for (word in POLITE) reply = reply.removeSuffix(" $word").removePrefix("$word ")
        return when (reply) {
            in YES -> true
            in NO -> false
            else -> null
        }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace("'", "").replace("’", "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            // "כן כן" and "yes yes" are still a yes.
            .split(' ').distinct().joinToString(" ")
}
