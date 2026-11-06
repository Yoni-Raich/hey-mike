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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androidagent.core.EngineKind
import dev.androidagent.core.proactive.AlertLevel
import dev.androidagent.core.proactive.AlertState
import dev.androidagent.core.proactive.ConnectorIds
import dev.androidagent.core.proactive.ConnectorState
import dev.androidagent.core.proactive.ConnectorStatus
import dev.androidagent.core.proactive.ProfileEntry
import dev.androidagent.core.proactive.ProfileKind
import dev.androidagent.core.proactive.ProfileOrigin

// ----- Connections, alerts and "what matters to me" -------------------------------

/** The top of the Sources page: the read-only connections, and the way into alerts and the profile. */
@Composable
internal fun ConnectionsSection(state: AgentUiState, actions: AgentUiActions, openAlerts: () -> Unit, openProfile: () -> Unit) {
    val desk = state.taskDesk
    LaunchedEffect(Unit) { actions.onRefreshConnections() }
    SectionLabel(taskText("Connections", "חיבורים"), 2, MaterialTheme.colorScheme.onSurfaceVariant)
    ConnectionCard(
        icon = Icons.Outlined.ChatBubbleOutline,
        title = "WhatsApp Web",
        explain = taskText("Linked inside Mike's own browser. When a WhatsApp notification needs more context, Mike reads the recent messages of that chat in the background. Read-only: Mike never sends or marks as read from here.",
            "מקושר בתוך הדפדפן של מייק. כשהתראה מווטסאפ צריכה עוד הקשר, מייק קורא ברקע את ההודעות האחרונות של אותה שיחה. קריאה בלבד: מייק לא שולח ולא מסמן כנקרא."),
        risk = taskText("Mike only reads chats already saved in this browser and never contacts WhatsApp for more. WhatsApp may still log out linked devices; then link again.",
            "מייק קורא רק שיחות ששמורות כבר בדפדפן הזה, ולא מבקש מווטסאפ שום דבר נוסף. ווטסאפ עשויה לנתק מכשירים מקושרים, ואז מקשרים שוב."),
        status = desk.connections[ConnectorIds.WHATSAPP_WEB],
        use = desk.settings.connectors[ConnectorIds.WHATSAPP_WEB] == true,
        onUse = { actions.onConnectorUse(ConnectorIds.WHATSAPP_WEB, it) },
        onLink = { actions.onConnectorLink(ConnectorIds.WHATSAPP_WEB) },
        onUnlink = { actions.onConnectorUnlink(ConnectorIds.WHATSAPP_WEB) },
    )
    ConnectionCard(
        icon = Icons.Outlined.Email,
        title = "Gmail",
        explain = taskText("Google sign-in with read-only mail access. Mike reads the thread behind a Gmail notification and can look up earlier mail from the same sender.",
            "התחברות לגוגל עם הרשאת קריאה בלבד למייל. מייק קורא את השרשור שמאחורי התראה מג׳ימייל ויכול לחפש מיילים קודמים מאותו שולח."),
        risk = taskText("Simpler: if Gmail is connected in your Claude account, switch it on below instead.",
            "פשוט יותר: אם ג׳ימייל מחובר בחשבון Claude שלך, הפעילו אותו למטה במקום."),
        status = desk.connections[ConnectorIds.GMAIL],
        use = desk.settings.connectors[ConnectorIds.GMAIL] == true,
        onUse = { actions.onConnectorUse(ConnectorIds.GMAIL, it) },
        onLink = { actions.onConnectorLink(ConnectorIds.GMAIL) },
        onUnlink = { actions.onConnectorUnlink(ConnectorIds.GMAIL) },
    )
    AccountConnectorsCard(state, actions)
    val alerts = desk.profile.alerts
    NavRow(Icons.Outlined.Notifications, taskText("Alerts", "התראות"),
        if (!alerts.enabled) taskText("Off: Mike writes only in the Manager chat", "כבויות: מייק כותב רק בצ׳אט המנהל")
        else taskText("Quiet ${hm(alerts.quietStartMinute)}–${hm(alerts.quietEndMinute)} · up to ${alerts.urgentPerDay} urgent a day",
            "שקט ${hm(alerts.quietStartMinute)}–${hm(alerts.quietEndMinute)} · עד ${alerts.urgentPerDay} דחופות ביום"), openAlerts)
    val owner = desk.profile.entries.count { it.origin == ProfileOrigin.OWNER }
    val learned = desk.profile.entries.count { it.origin == ProfileOrigin.LEARNED }
    NavRow(Icons.Outlined.Psychology, taskText("What matters to me", "מה חשוב לי"),
        taskText("$owner set by you · $learned learned", "$owner שקבעת · $learned שמייק למד"), openProfile)
}

/**
 * Connectors the user already connected in their Claude account (claude.ai ›
 * Settings › Connectors). A background pass that runs on Claude may read
 * through the switched-on ones, with the read-only tools only.
 */
@Composable
private fun AccountConnectorsCard(state: AgentUiState, actions: AgentUiActions) {
    val desk = state.taskDesk
    val accounts = desk.accounts.filter { it.engine == EngineKind.CLAUDE }
    Column(Modifier.fillMaxWidth().border(1.dp, ControlLine.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Cloud, null, Modifier.size(22.dp))
            Text(taskText("Through your Claude account", "דרך חשבון Claude שלך"), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(taskText("Gmail, Calendar or Drive you connected in claude.ai work here with no extra sign-in. Mike uses only the tools the service marks read-only; deleting, labels and drafts stay blocked.",
            "ג׳ימייל, יומן או דרייב שחיברת ב־claude.ai עובדים כאן בלי התחברות נוספת. מייק משתמש רק בכלים שהשירות מסמן כקריאה בלבד; מחיקה, תוויות וטיוטות חסומות."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (desk.settings.passEngine != EngineKind.CLAUDE) Text(
            taskText("Used only when background checks run on Claude. They run on Codex now; Codex account connectors are not supported yet.",
                "פעיל רק כשהבדיקות ברקע רצות על Claude. כרגע הן רצות על Codex, ובחיבורי החשבון של Codex עדיין אין תמיכה."),
            style = MaterialTheme.typography.bodySmall, color = BoardAmber)
        if (accounts.isEmpty() && !desk.accountsChecked) Text(
            taskText("Checking your Claude account…", "בודק את חשבון Claude שלך…"),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (accounts.isEmpty()) Text(
            taskText("No connectors found yet. Sign in to Claude in Settings and connect services at claude.ai › Settings › Connectors.",
                "עוד לא נמצאו חיבורים. התחברו ל־Claude בהגדרות וחברו שירותים ב־claude.ai › הגדרות › Connectors."),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        accounts.forEach { account ->
            val key = dev.androidagent.core.proactive.ContextRouter.key(account)
            val detail = when {
                !account.connected -> taskText("Not connected in claude.ai", "לא מחובר ב־claude.ai")
                account.readTools.isEmpty() -> taskText("No read-only tools", "אין כלים לקריאה בלבד")
                else -> taskText("${account.readTools.size} read-only tools · ${account.writeTools} others blocked",
                    "${account.readTools.size} כלי קריאה · ${account.writeTools} אחרים חסומים")
            }
            if (account.connected && account.readTools.isNotEmpty()) {
                SwitchRow(Icons.Outlined.Visibility, account.name, detail, desk.settings.connectors[key] == true) { on -> actions.onConnectorUse(key, on) }
            } else {
                Text("${account.name} · $detail", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectionCard(icon: ImageVector, title: String, explain: String, risk: String?, status: ConnectorStatus?, use: Boolean,
    onUse: (Boolean) -> Unit, onLink: () -> Unit, onUnlink: () -> Unit) {
    val state = status?.state
    val linked = state == ConnectorState.READY || state == ConnectorState.NEEDS_ATTENTION
    Column(Modifier.fillMaxWidth().border(1.dp, ControlLine.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(statusText(status), style = MaterialTheme.typography.labelMedium, color = when (state) {
                    ConnectorState.READY -> BoardTeal
                    ConnectorState.NEEDS_ATTENTION -> BoardAmber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            }
        }
        Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        risk?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BoardAmber) }
        if (linked) SwitchRow(Icons.Outlined.Visibility, taskText("Use to understand messages", "להשתמש כדי להבין הודעות"),
            taskText("Only for apps and chats allowed on this page", "רק לאפליקציות ושיחות שמותרות בעמוד הזה"), use, onUse)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onLink, border = BorderStroke(1.dp, ControlLine)) {
                Text(when {
                    state == ConnectorState.NEEDS_ATTENTION -> taskText("Reconnect", "חיבור מחדש")
                    linked -> taskText("Open", "פתיחה")
                    state == ConnectorState.LINKING -> taskText("Continue linking", "המשך קישור")
                    else -> taskText("Connect", "חיבור")
                })
            }
            if (linked || state == ConnectorState.LINKING) TextButton(onClick = onUnlink) {
                Text(taskText("Disconnect", "ניתוק"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun statusText(status: ConnectorStatus?): String {
    val base = when (status?.state) {
        null -> taskText("Checking…", "בודק…")
        ConnectorState.OFF -> taskText("Off", "כבוי")
        ConnectorState.NOT_LINKED -> taskText("Not connected", "לא מחובר")
        ConnectorState.LINKING -> taskText("Linking…", "בתהליך קישור…")
        ConnectorState.READY -> taskText("Connected", "מחובר")
        ConnectorState.NEEDS_ATTENTION -> taskText("Needs attention", "דורש טיפול")
    }
    return listOfNotNull(base, status?.account, status?.detail?.takeIf { status.state != ConnectorState.READY }).joinToString(" · ")
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun hm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(label: String, options: List<Int>, selected: (Int) -> Boolean, text: (Int) -> String, pick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { value -> FilterChip(selected = selected(value), onClick = { pick(value) }, label = { Text(text(value)) }) }
    }
}

@Composable
internal fun AlertsPage(state: AgentUiState, actions: AgentUiActions) {
    val prefs = state.taskDesk.profile.alerts
    Text(taskText("Mike tells you when something needs you. Urgent alerts vibrate and pop up; the rest wait for a quiet notice or the digest.",
        "מייק מודיע לך כשמשהו דורש אותך. התראה דחופה רוטטת וקופצת; השאר מגיע כהתראה שקטה או בסיכום."),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwitchRow(Icons.Outlined.Notifications, taskText("Alerts from Mike", "התראות ממייק"),
        taskText("Off: everything stays in the Manager chat", "כשכבוי, הכול נשאר בצ׳אט המנהל"), prefs.enabled) { on -> actions.onAlertPrefs { it.copy(enabled = on) } }
    if (!prefs.enabled) return
    ChoiceRow(taskText("Quiet from", "שקט החל מ־"), listOf(21, 22, 23, 0).map { it * 60 }, { it == prefs.quietStartMinute }, ::hm) { m ->
        actions.onAlertPrefs { it.copy(quietStartMinute = m) }
    }
    ChoiceRow(taskText("Quiet until", "שקט עד"), listOf(6, 7, 8, 9).map { it * 60 }, { it == prefs.quietEndMinute }, ::hm) { m ->
        actions.onAlertPrefs { it.copy(quietEndMinute = m) }
    }
    Text(taskText("In quiet hours only people and chats you starred with \"always tell me\" can break through.",
        "בשעות השקט רק אנשים ושיחות שסימנת \"תמיד להודיע\" יכולים לעבור."), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceRow(taskText("Digest times", "שעות הסיכום"), listOf(8, 13, 18, 21).map { it * 60 }, { it in prefs.digestMinutes }, ::hm) { m ->
        actions.onAlertPrefs { it.copy(digestMinutes = if (m in it.digestMinutes) it.digestMinutes - m else (it.digestMinutes + m).sorted()) }
    }
    ChoiceRow(taskText("Urgent alerts per day", "התראות דחופות ביום"), listOf(1, 3, 5, 10), { it == prefs.urgentPerDay }, { "$it" }) { n ->
        actions.onAlertPrefs { it.copy(urgentPerDay = n) }
    }
    ChoiceRow(taskText("Quiet notices per day", "התראות שקטות ביום"), listOf(5, 10, 20), { it == prefs.noticePerDay }, { "$it" }) { n ->
        actions.onAlertPrefs { it.copy(noticePerDay = n) }
    }
    SwitchRow(Icons.Outlined.Notifications, taskText("Sound for urgent alerts", "צליל להתראות דחופות"),
        taskText("Off by default: urgent alerts only vibrate", "כבוי כברירת מחדל: התראה דחופה רק רוטטת"), prefs.urgentSound) { on ->
        actions.onAlertPrefs { it.copy(urgentSound = on) }
    }
    OutlinedButton(onClick = actions.onTestAlert, border = BorderStroke(1.dp, ControlLine), modifier = Modifier.fillMaxWidth()) {
        Text(taskText("Send a test alert", "שליחת התראת בדיקה"))
    }
    val recent = state.taskDesk.recentAlerts
    if (recent.isNotEmpty()) {
        SectionLabel(taskText("Recent alerts", "התראות אחרונות"), recent.size, MaterialTheme.colorScheme.onSurfaceVariant)
        recent.take(15).forEach { alert ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(alert.title, style = MaterialTheme.typography.bodyMedium)
                Text(listOf(levelText(alert.level), stateText(alert.state), shortTime(alert.createdAt)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text(taskText("\"Not important\" on an alert teaches Mike. After three in two weeks from the same place, Mike lowers it by itself.",
        "\"לא חשוב\" על התראה מלמד את מייק. אחרי שלוש פעמים בשבועיים מאותו מקום, מייק מוריד אותו בעצמו."),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun levelText(level: AlertLevel) = when (level) {
    AlertLevel.URGENT -> taskText("Urgent", "דחוף")
    AlertLevel.NOTICE -> taskText("Notice", "שקט")
    AlertLevel.DIGEST -> taskText("Digest", "סיכום")
    AlertLevel.NONE -> taskText("Held back", "לא נשלח")
}

private fun stateText(state: AlertState) = when (state) {
    AlertState.PENDING -> taskText("waiting", "ממתין")
    AlertState.SHOWN -> taskText("shown", "הוצג")
    AlertState.OPENED -> taskText("opened", "נפתח")
    AlertState.NOT_IMPORTANT -> taskText("not important", "לא חשוב")
    AlertState.SNOOZED -> taskText("later", "נדחה")
    AlertState.DISMISSED -> taskText("dismissed", "נסגר")
    AlertState.DIGESTED -> taskText("in digest", "בסיכום")
    AlertState.REPLACED -> taskText("replaced", "הוחלף")
    AlertState.SUPPRESSED -> taskText("held back", "נעצר")
}

private fun kindText(kind: ProfileKind) = when (kind) {
    ProfileKind.PERSON -> taskText("Person", "אדם")
    ProfileKind.CONVERSATION -> taskText("Chat", "שיחה")
    ProfileKind.SOURCE -> taskText("App", "אפליקציה")
    ProfileKind.TOPIC -> taskText("Topic", "נושא")
}

private fun weightText(weight: Int) = when {
    weight >= 2 -> taskText("Important", "חשוב")
    weight == 1 -> taskText("A bit more", "קצת יותר")
    weight == 0 -> taskText("Normal", "רגיל")
    weight == -1 -> taskText("Less", "פחות")
    else -> taskText("Muted", "מושתק")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfilePage(state: AgentUiState, actions: AgentUiActions) {
    val profile = state.taskDesk.profile
    Text(taskText("What Mike knows about what matters to you. Your choices always win; Mike's guesses stay small and you can correct them.",
        "מה שמייק יודע על מה שחשוב לך. הבחירות שלך תמיד קובעות; הניחושים של מייק נשארים קטנים ואפשר לתקן אותם."),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionLabel(taskText("Add", "הוספה"), 0, MaterialTheme.colorScheme.onSurfaceVariant)
    var kind by rememberSaveable { mutableStateOf(ProfileKind.PERSON) }
    var name by rememberSaveable { mutableStateOf("") }
    var always by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindText(k)) }) }
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = {
        Text(when (kind) {
            ProfileKind.PERSON -> taskText("Name as it shows in notifications", "השם כפי שמופיע בהתראות")
            ProfileKind.CONVERSATION -> taskText("Chat or group name", "שם השיחה או הקבוצה")
            ProfileKind.SOURCE -> taskText("App package, e.g. com.whatsapp", "חבילת האפליקציה, למשל com.whatsapp")
            ProfileKind.TOPIC -> taskText("For example: school, bank", "למשל: בית ספר, בנק")
        })
    })
    if (kind != ProfileKind.SOURCE && kind != ProfileKind.TOPIC) SwitchRow(Icons.Outlined.Notifications,
        taskText("Always tell me", "תמיד להודיע לי"), taskText("Even in quiet hours and over the daily limit", "גם בשעות השקט ומעבר למגבלה היומית"), always) { always = it }
    if (name.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onProfileSet(kind, name.trim(), null, 2, always); name = ""; always = false },
            border = BorderStroke(1.dp, BoardTeal)) { Text(taskText("Important", "חשוב")) }
        OutlinedButton(onClick = { actions.onProfileSet(kind, name.trim(), null, -2, false); name = ""; always = false },
            border = BorderStroke(1.dp, ControlLine)) { Text(taskText("Mute", "להשתיק")) }
    }
    if (kind == ProfileKind.CONVERSATION) Text(taskText("Chat names are matched in every app. To name one app, write app|chat.",
        "שם שיחה מתאים בכל האפליקציות. לאפליקציה אחת: app|שיחה."), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    val owner = profile.entries.filter { it.origin == ProfileOrigin.OWNER }.sortedWith(compareByDescending<ProfileEntry> { it.weight }.thenBy { it.label })
    SectionLabel(taskText("Set by you", "קבעת בעצמך"), owner.size, MaterialTheme.colorScheme.onSurfaceVariant)
    if (owner.isEmpty()) Text(taskText("Nothing yet. You can also tell the Manager in chat: \"always tell me about Dana\".",
        "עדיין כלום. אפשר גם לכתוב למנהל בצ׳אט: \"תמיד תודיע לי על דנה\"."), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    owner.forEach { entry ->
        EntryRow(entry, listOfNotNull(kindText(entry.kind), weightText(entry.weight),
            taskText("always tell me", "תמיד להודיע").takeIf { entry.alwaysNotify }).joinToString(" · ")) {
            TextButton(onClick = { actions.onProfileRemove(entry.id) }) { Text(taskText("Remove", "הסרה")) }
        }
    }

    val learned = profile.entries.filter { it.origin == ProfileOrigin.LEARNED }.sortedByDescending { it.updatedAt }
    SectionLabel(taskText("Learned by Mike", "מייק למד"), learned.size, MaterialTheme.colorScheme.onSurfaceVariant)
    if (learned.isEmpty()) Text(taskText("Mike learns from what you open, what you call not important, and what you approve.",
        "מייק לומד ממה שפתחת, ממה שסימנת כלא חשוב ומהמשימות שאישרת."), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    learned.forEach { entry ->
        EntryRow(entry, listOfNotNull(kindText(entry.kind), weightText(entry.weight), entry.why).joinToString(" · ")) {
            TextButton(onClick = { actions.onProfileReject(entry.id) }) { Text(taskText("Wrong", "לא נכון")) }
        }
    }
}

@Composable
private fun EntryRow(entry: ProfileEntry, detail: String, action: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
        }
    }
}
