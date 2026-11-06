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

package dev.androidagent.core.proactive

import java.time.ZoneId
import kotlinx.serialization.Serializable

/** How loudly Mike may reach the user. Ordered from silent to loud. */
@Serializable
enum class AlertLevel {
    NONE, DIGEST, NOTICE, URGENT;
    val wire: String get() = name.lowercase()
    fun lower(): AlertLevel = entries[(ordinal - 1).coerceAtLeast(0)]
    companion object { fun parse(text: String?): AlertLevel? = entries.firstOrNull { it.wire == text?.trim()?.lowercase() } }
}

/** What a model asked for, before the app's rules. */
data class AlertRequest(
    val level: AlertLevel,
    val app: String? = null,
    val conversation: String? = null,
    val sender: String? = null,
    val topic: String? = null,
)

/** The app's final word, with the reasons in the order they applied. */
data class AlertDecision(val level: AlertLevel, val requested: AlertLevel, val reasons: List<String>) {
    val changed: Boolean get() = level != requested
}

/** Today's delivered counts, from the alert book. */
data class AlertCounts(val urgent: Int = 0, val notice: Int = 0)

/**
 * The rules between a model's request and the user's phone. A model can ask;
 * only this decides. The profile and budgets can lower a level; nothing here
 * raises one above what was asked.
 */
object AlertGate {
    fun decide(
        request: AlertRequest,
        prefs: AlertPrefs,
        profile: ProfileMatch,
        today: AlertCounts,
        lastUrgentForConversation: Long?,
        now: Long,
        zone: ZoneId,
    ): AlertDecision {
        val reasons = mutableListOf<String>()
        var level = request.level
        fun done() = AlertDecision(level, request.level, reasons.toList())
        if (level == AlertLevel.NONE) return done()
        if (!prefs.enabled) { reasons += "alerts_off"; level = AlertLevel.NONE; return done() }
        profile.muted?.let { reasons += "muted_${it.kind.wire}"; level = AlertLevel.NONE; return done() }
        if (!profile.starred && !profile.alwaysNotify) profile.learnedLow?.let {
            reasons += "learned_low_${it.kind.wire}"
            level = level.lower()
        }
        if (level >= AlertLevel.NOTICE && prefs.quietAt(now, zone)) {
            if (level == AlertLevel.URGENT && (profile.starred || profile.alwaysNotify)) reasons += "quiet_hours_allowed"
            else { reasons += "quiet_hours"; level = AlertLevel.DIGEST }
        }
        if (level == AlertLevel.URGENT && lastUrgentForConversation != null && now - lastUrgentForConversation < prefs.conversationCooldownMs) {
            reasons += "cooldown"; level = AlertLevel.NOTICE
        }
        if (level == AlertLevel.URGENT && today.urgent >= prefs.urgentPerDay) { reasons += "budget_urgent"; level = AlertLevel.NOTICE }
        if (level == AlertLevel.NOTICE && today.notice >= prefs.noticePerDay && !profile.alwaysNotify) { reasons += "budget_notice"; level = AlertLevel.DIGEST }
        return done()
    }

    /** Words for a reason code, for the alert's detail and the Coach. */
    fun explain(reason: String, hebrew: Boolean): String = when {
        reason == "alerts_off" -> if (hebrew) "ההתראות כבויות" else "alerts are off"
        reason.startsWith("muted_") -> if (hebrew) "סימנת שזה לא חשוב" else "you muted this"
        reason.startsWith("learned_low_") -> if (hebrew) "למדתי שזה פחות חשוב לך" else "learned this matters less to you"
        reason == "quiet_hours" -> if (hebrew) "שעות שקט: נשמר לסיכום" else "quiet hours: kept for the digest"
        reason == "quiet_hours_allowed" -> if (hebrew) "בשעות שקט, כי סימנת שזה תמיד חשוב" else "during quiet hours, because you starred it"
        reason == "cooldown" -> if (hebrew) "כבר הודעתי על השיחה הזו לא מזמן" else "already alerted about this conversation recently"
        reason == "budget_urgent" -> if (hebrew) "הגעתי למכסת ההתראות הדחופות להיום" else "today's urgent alerts are used up"
        reason == "budget_notice" -> if (hebrew) "הגעתי למכסת ההתראות להיום: נשמר לסיכום" else "today's alerts are used up: kept for the digest"
        else -> reason
    }
}
