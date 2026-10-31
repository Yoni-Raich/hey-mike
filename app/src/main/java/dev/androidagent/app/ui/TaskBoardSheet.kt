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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.androidagent.app.TaskDeskState
import dev.androidagent.core.DismissReason
import dev.androidagent.core.AgentModel
import dev.androidagent.core.EngineKind
import dev.androidagent.core.MemberKind
import dev.androidagent.core.MemoryNote
import dev.androidagent.core.MikeTask
import dev.androidagent.core.TaskCategory
import dev.androidagent.core.TaskStatus
import dev.androidagent.core.TeamMember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The board's own two colours, borrowed from the standing-rules strip so the
// two features read as one family: teal is live and fine, amber waits for you.
private val BoardTeal = Color(0xFF7DE4D4)
private val BoardAmber = Color(0xFFF6B86A)
private val AmberContainer = Color(0xFF25221B)
private val AmberOutline = Color(0xFF5B4A32)
private val SheetFill = Color(0xFF101918)
private val ControlLine = Color(0xFF536A67)
private val ResultTint = Color(0xFF18332F)

/** Preserve mixed Hebrew/English direction and render simple emphasis in model text. */
@Composable
private fun TaskContent(value: String, style: TextStyle, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface, maxLines: Int = Int.MAX_VALUE) {
    val text = remember(value) {
        buildAnnotatedString {
            var start = 0
            val bold = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
            bold.findAll(value).forEach { match ->
                append(value.substring(start, match.range.first))
                val begin = length
                append(match.groups[1]?.value ?: match.groups[2]?.value.orEmpty())
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), begin, length)
                start = match.range.last + 1
            }
            append(value.substring(start))
        }
    }
    Text(text, modifier = modifier.fillMaxWidth(), style = style.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start),
        color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Follow the phone's language; user-written text keeps its own direction. */
internal fun taskText(english: String, hebrew: String): String =
    if (Locale.getDefault().language in setOf("he", "iw")) hebrew else english

/**
 * The task board, its cards, the team, memory and sources, in one sheet.
 *
 * Two levels like [AutomationsSheet]: back walks a card or member to its list,
 * a list to the board, the board to closed. Every decision here is a tap that
 * goes straight to the board's decision; nothing on this screen starts work
 * any other way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskBoardSheet(state: AgentUiState, actions: AgentUiActions) {
    val desk = state.taskDesk
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by rememberSaveable(state.taskBoardPage) { mutableStateOf(state.taskBoardPage) }
    val back: () -> Unit = {
        page = when {
            page == null -> { actions.onCloseTaskBoard(); null }
            page!!.startsWith("member:") -> "team"
            page == "memory" -> "sources"
            else -> null
        }
    }
    ModalBottomSheet(
        onDismissRequest = actions.onCloseTaskBoard,
        sheetState = sheetState,
        containerColor = SheetFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.fillMaxHeight(0.94f),
    ) {
        BackHandler { back() }
        val scroll = rememberScrollState()
        LaunchedEffect(page) { scroll.scrollTo(0) }
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = page
            val card = current?.removePrefix("card:")?.takeIf { current.startsWith("card:") }?.let { id -> desk.tasks.firstOrNull { it.id == id } }
            val member = current?.removePrefix("member:")?.takeIf { current.startsWith("member:") }?.let { id -> desk.members.firstOrNull { it.id == id } }
            SheetHeader(
                title = when {
                    card != null -> taskText("Task", "משימה")
                    member != null -> member.name
                    current == "team" -> taskText("Mike's team", "הצוות של מייק")
                    current == "sources" -> taskText("Sources", "מקורות והגדרות")
                    current == "memory" -> taskText("Memory", "זיכרון")
                    else -> taskText("Tasks", "משימות")
                },
                subtitle = when {
                    current == null -> boardSubtitle(desk)
                    current == "team" -> taskText("They work in the background. Open anyone to see what they did.", "הצוות עובד ברקע. אפשר להיכנס לכל אחד ולראות מה עשה.")
                    else -> null
                },
                onBack = back,
                trailing = if (current == null) ({
                    IconButton(onClick = { page = "team" }) { Icon(Icons.Outlined.Groups, taskText("Mike's team", "הצוות של מייק")) }
                    IconButton(onClick = { page = "sources" }) { Icon(Icons.Outlined.Tune, taskText("Sources and settings", "מקורות והגדרות")) }
                }) else null,
            )
            desk.error?.let { ErrorLine(it, actions.onTaskClearError) }
            when {
                card != null -> TaskDetail(card, desk, actions)
                member != null -> MemberPage(member, state, actions) { id -> page = "card:$id" }
                current == "team" -> TeamPage(state, actions) { id -> page = "member:$id" }
                current == "sources" -> SourcesPage(state, actions) { page = "memory" }
                current == "memory" -> MemoryPage(desk, actions)
                else -> BoardPage(state, actions, open = { id -> page = "card:$id" }, openSources = { page = "sources" })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String?, onBack: () -> Unit, trailing: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, taskText("Back", "חזרה")) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ErrorLine(text: String, dismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Dismiss") }
        }
    }
}

internal fun boardSubtitle(desk: TaskDeskState): String {
    if (!desk.settings.discoveryOn) return taskText("Background discovery is off", "החיפוש ברקע כבוי")
    desk.error?.let { return taskText("Discovery needs attention", "החיפוש דורש בדיקה") }
    val last = desk.settings.lastSuccessfulPassAt ?: return taskText("No completed discovery pass yet", "עדיין לא הושלם סבב חיפוש")
    return taskText("Mike looked ", "מייק בדק ") + ago(last)
}

internal fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> taskText("just now", "הרגע")
        minutes < 60 -> taskText("$minutes min ago", "לפני $minutes דקות")
        minutes < 24 * 60 -> taskText("${minutes / 60} h ago", "לפני ${minutes / 60} שעות")
        else -> taskText("${minutes / (24 * 60)} days ago", "לפני ${minutes / (24 * 60)} ימים")
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

internal fun shortTime(at: Long): String {
    val zoned = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    return if (zoned.toLocalDate() == today) TIME.format(zoned) else DAY.format(zoned) + " " + TIME.format(zoned)
}

// ----- Board ------------------------------------------------------------------------

@Composable
private fun BoardPage(state: AgentUiState, actions: AgentUiActions, open: (String) -> Unit, openSources: () -> Unit) {
    val desk = state.taskDesk
    val tasks = desk.tasks
    val waiting = tasks.filter { it.status.undecided && it.status != TaskStatus.SNOOZED }
        .sortedWith(compareBy<MikeTask> { it.usefulUntil ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
    val onIt = tasks.filter { it.status in setOf(TaskStatus.APPROVED, TaskStatus.RUNNING, TaskStatus.WAITING) }
    val check = tasks.filter { it.status in setOf(TaskStatus.REVIEW, TaskStatus.FAILED, TaskStatus.STOPPED, TaskStatus.UNKNOWN) }
        .sortedByDescending { if (it.category == TaskCategory.REPORT) Long.MAX_VALUE else it.updatedAt }
    val reports = tasks.filter { it.category == TaskCategory.REPORT && it.status == TaskStatus.PROPOSED }
    val yours = tasks.filter { it.status == TaskStatus.MINE }
    val history = tasks.filter { it.status.closed || it.status == TaskStatus.SNOOZED }.sortedByDescending { it.updatedAt }

    if (!desk.settings.discoveryOn && tasks.none { !it.status.closed }) DiscoveryOffCard(state, actions, openSources)
    ManagerRow(desk, state, actions)
    DiscoveryStatus(desk)

    if (tasks.none { !it.status.closed }) {
        Text(
            if (desk.settings.discoveryOn) taskText("Nothing waits for you. Mike keeps looking in the background.", "אין כרגע משימות שמחכות לך. מייק ממשיך לחפש ברקע.")
            else taskText("No tasks yet. Turn on discovery, or tell Mike about something to follow up.", "עדיין אין משימות. אפשר להפעיל חיפוש או לדבר עם מייק על משהו שצריך לקדם."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
    val toCheck = (reports + check).distinctBy { it.id }
    val decide = waiting.filterNot { it.category == TaskCategory.REPORT }
    if (decide.isNotEmpty()) {
        SectionLabel(taskText("Waiting for your decision", "מחכה להחלטה שלך"), decide.size, BoardAmber)
        var showAll by rememberSaveable { mutableStateOf(false) }
        (if (showAll) decide else decide.take(3)).forEach { TaskCard(it, desk, actions) { open(it.id) } }
        if (decide.size > 3 && !showAll) TextButton(onClick = { showAll = true }) { Text(taskText("${decide.size - 3} more", "עוד ${decide.size - 3}"), color = BoardAmber) }
    }
    if (onIt.isNotEmpty()) {
        SectionLabel(taskText("Mike is on it", "מייק מטפל בזה"), onIt.size, BoardTeal)
        onIt.forEach { TaskCard(it, desk, actions) { open(it.id) } }
    }
    if (toCheck.isNotEmpty()) {
        SectionLabel(taskText("To check", "מוכן לבדיקה"), toCheck.size, BoardTeal)
        toCheck.forEach { TaskCard(it, desk, actions) { open(it.id) } }
    }
    if (yours.isNotEmpty()) {
        SectionLabel(taskText("Yours", "בטיפול שלך"), yours.size, MaterialTheme.colorScheme.onSurfaceVariant)
        yours.forEach { YoursRow(it, actions) { open(it.id) } }
    }
    if (history.isNotEmpty()) {
        var all by rememberSaveable { mutableStateOf(false) }
        SectionLabel(taskText("History", "היסטוריה"), history.size, MaterialTheme.colorScheme.onSurfaceVariant)
        (if (all) history.take(40) else history.take(3)).forEach { HistoryRow(it, actions) { open(it.id) } }
        if (history.size > 3 && !all) TextButton(onClick = { all = true }) { Text(taskText("Show ${minOf(history.size, 40) - 3} more", "הצגת עוד ${minOf(history.size, 40) - 3}")) }
    }
}

@Composable
private fun DiscoveryStatus(desk: TaskDeskState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val briefingWait = when(desk.briefingWait) {
        "cap" -> taskText("Manager briefings reached today's limit", "תדריכי המנהל הגיעו למכסה היומית")
        "quota" -> taskText("Manager is waiting for model quota", "המנהל ממתין לחידוש מכסת המודל")
        "interval" -> taskText("A follow-up is waiting for the next Manager review", "מעקב ממתין לסקירת המנהל הבאה")
        "pending" -> taskText("Manager has a briefing to prepare", "המנהל צריך להכין תדריך")
        else -> null
    }
    briefingWait?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = BoardAmber) }
    val status = when(desk.waitReason) {
        "running" -> taskText("The team is processing new input", "הצוות מעבד מידע חדש")
        "quota" -> taskText("Waiting for model usage to recover", "ממתין לחידוש מכסת השימוש במודל")
        "cap" -> taskText("Daily pass limit reached", "החיפוש הגיע למכסה היומית")
        "off" -> taskText("Background discovery is paused", "החיפוש ברקע מושהה")
        "idle" -> taskText("Listening for new input", "ממתין למידע חדש")
        "excluded" -> taskText("Waiting input is excluded or has unverified old sources", "המידע שממתין מוחרג או נאסף ממקור ישן שלא אומת")
        "backpressure" -> taskText("Inbox full. Waiting for the next processing pass", "התור מלא. ממתין לסבב העיבוד הבא")
        else -> taskText("Collecting a batch before the next pass", "אוסף מידע לסבב הבא")
    }
    Surface(color = Color(0xFF1A2523), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { LiveLine(status, if(desk.waitReason in setOf("cap", "quota", "off", "backpressure")) BoardAmber else BoardTeal) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) taskText("Less", "פחות") else taskText("Details", "פרטים"), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (expanded) Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(taskText("${desk.pendingSignals} inputs waiting · ${desk.settings.passesToday}/${desk.settings.dailyPassCap} passes today",
                    "${desk.pendingSignals} פריטי מידע ממתינים · ${desk.settings.passesToday}/${desk.settings.dailyPassCap} סבבים היום"), style = MaterialTheme.typography.labelMedium)
                desk.nextCheckAt?.takeIf { desk.waitReason in setOf("waiting", "backpressure") }?.let {
                    Text(taskText("Next eligible check: ${shortTime(it)}", "הסבב הבא יוכל להתחיל ב־${shortTime(it)}"), style = MaterialTheme.typography.labelMedium)
                }
                if(desk.settings.captureGaps > 0) Text(taskText("${desk.settings.captureGaps} inputs were missed or expired. Coverage is partial.",
                    "${desk.settings.captureGaps} פריטים לא נאספו או פגו. הכיסוי חלקי."), color = BoardAmber, style = MaterialTheme.typography.labelMedium)
                if(desk.blockedSignals > 0) Text(taskText("${desk.blockedSignals} inputs stay outside model context because of source policy.",
                    "${desk.blockedSignals} פריטים לא מועברים למודל בגלל הגדרות המקורות."), color = BoardAmber, style = MaterialTheme.typography.labelMedium)
            }
            if (!expanded && (desk.settings.captureGaps > 0 || desk.blockedSignals > 0)) {
                Text(if (desk.blockedSignals > 0) taskText("Some input is excluded by your source settings", "חלק מהמידע מוחרג בהגדרות המקורות")
                    else taskText("Earlier input was missed or expired", "חלק מהמידע הקודם לא נאסף או פג תוקפו"), Modifier.padding(bottom = 8.dp),
                    color = if (desk.blockedSignals > 0) BoardAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DiscoveryOffCard(state: AgentUiState, actions: AgentUiActions, openSources: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MikeEye(EyeMood.PAUSED, Modifier.size(40.dp), animate = false)
                Text(taskText("Let Mike find tasks for you", "לתת למייק למצוא עבורך משימות"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                taskText("Mike reads new notifications (WhatsApp, mail and other apps), your calendar and chats that went quiet, and " +
                    "puts what needs doing here. Work starts after you choose. Verification codes are filtered out, raw text is " +
                    "deleted once used, and you can exclude any app.",
                    "מייק אוסף התראות חדשות, מהיומן ומשיחות קודמות, ומציע כאן משימות. העבודה מתחילה אחרי הבחירה שלך. קודי אימות מסוננים, הטקסט הגולמי נמחק לאחר עיבוד, ואפשר להחריג כל אפליקציה."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { actions.onTaskDiscovery(true) }, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                    Text(taskText("Turn on", "הפעלת החיפוש"))
                }
                TextButton(onClick = openSources) { Text(taskText("Choose sources", "בחירת מקורות")) }
            }
            if (!state.automations.notificationAccess) {
                Text(taskText("Notification access is off, so messages cannot be read yet.", "הגישה להתראות כבויה. כדי לקרוא הודעות צריך להפעיל אותה."), style = MaterialTheme.typography.labelMedium, color = BoardAmber)
                OutlinedButton(onClick = actions.onOpenNotificationAccess, border = BorderStroke(1.dp, ControlLine)) { Text(taskText("Allow notification access", "מתן גישה להתראות")) }
            }
        }
    }
}

@Composable
private fun ManagerRow(desk: TaskDeskState, state: AgentUiState, actions: AgentUiActions) {
    val manager = desk.managerSessionId ?: return
    val waiting = desk.tasks.count { it.status == TaskStatus.PROPOSED }
    val running = desk.tasks.count { it.status in setOf(TaskStatus.RUNNING, TaskStatus.APPROVED, TaskStatus.WAITING) }
    val scoutBusy = desk.activity.containsKey("scout") || desk.members.firstOrNull { it.kind == MemberKind.SCOUT }?.sessionId?.let { state.runs[it]?.active } == true
    val mood = managerMood(desk, scoutBusy)
    Surface(
        onClick = { actions.onOpenBoardChat(manager) },
        color = Color(0xFF182D2A),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, BoardTeal.copy(alpha = 0.23f)),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = taskText("Open the Manager chat", "פתיחת שיחת המנהל") },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MikeEye(mood, Modifier.size(60.dp), satellites = running)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(taskText("Mike · Manager", "מייק · המנהל"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val line = listOfNotNull(
                    waiting.takeIf { it > 0 }?.let { taskText("$it waiting for you", "$it מחכות לך") },
                    running.takeIf { it > 0 }?.let { taskText("$it running", "$it בביצוע") },
                ).joinToString(" · ").ifEmpty { taskText("Talk to Mike about the board", "לדבר עם מייק על המשימות") }
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (waiting > 0) BoardTeal else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ForwardChevron()
        }
    }
}

@Composable
private fun SectionLabel(text: String, count: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("$count", Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun ForwardChevron() {
    Icon(Icons.Outlined.ChevronRight, null,
        modifier = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Modifier.scale(-1f, 1f) else Modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ----- Cards ------------------------------------------------------------------------

private fun sourceIcon(task: MikeTask): ImageVector = when (task.evidence.firstOrNull()?.source ?: task.originKind) {
    "whatsapp", "message", "chat", "conversation", "backfill" -> Icons.Outlined.ChatBubbleOutline
    "email" -> Icons.Outlined.Email
    "calendar" -> Icons.Outlined.CalendarToday
    "review" -> Icons.Outlined.Bedtime
    "upkeep" -> Icons.Outlined.Psychology
    else -> Icons.Outlined.Notifications
}

private fun sourceName(source: String): String = when (source) {
        "whatsapp" -> "WhatsApp"; "email" -> taskText("Mail", "דואר"); "calendar" -> taskText("Calendar", "יומן"); "chat", "conversation", "backfill" -> taskText("Chat", "שיחה")
        "review" -> taskText("Nightly review", "סקירה לילית"); "upkeep" -> taskText("Memory upkeep", "סידור הזיכרון"); "signal", "notification" -> taskText("Notification", "התראה")
        else -> source.replaceFirstChar { it.uppercase() }
    }
private fun sourceLine(task: MikeTask): String {
    val first = task.evidence.firstOrNull()
    val source = sourceName(first?.source ?: task.originKind)
    return listOfNotNull(source, first?.sender ?: task.requestedBy, shortTime(first?.at ?: task.createdAt)).joinToString(" · ")
}

private fun placeLine(task: MikeTask): Pair<ImageVector, String> =
    if (task.computer == null) Icons.Outlined.PhoneAndroid to taskText("Phone", "טלפון")
    else Icons.Outlined.Computer to listOfNotNull(task.computer, task.project).joinToString(" · ")

private fun effectLabel(effect: String): String = when (effect.lowercase()) {
    "sends", "send" -> taskText("Sends a message", "שליחת הודעה")
    "pays", "pay" -> taskText("Spends money", "תשלום כסף")
    "deletes", "delete" -> taskText("Deletes something", "מחיקת מידע")
    "books", "book" -> taskText("Makes a booking", "ביצוע הזמנה")
    "posts", "post" -> taskText("Posts publicly", "פרסום פומבי")
    else -> effect.replaceFirstChar { it.uppercase() }
}

@Composable
private fun TaskCard(task: MikeTask, desk: TaskDeskState, actions: AgentUiActions, open: () -> Unit) {
    val amber = task.status == TaskStatus.WAITING || task.status == TaskStatus.UNKNOWN
    val failed = task.status == TaskStatus.FAILED
    Surface(
        onClick = open,
        color = when { amber -> AmberContainer; failed -> MaterialTheme.colorScheme.errorContainer; else -> Color(0xFF1A2624) },
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (amber) AmberOutline else Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetaRow(task)
            TaskContent(task.title, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
            when (task.status) {
                TaskStatus.PROPOSED, TaskStatus.SNOOZED -> ProposedBody(task)
                TaskStatus.APPROVED -> {
                    Text("Starting", style = MaterialTheme.typography.bodyMedium, color = BoardTeal)
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = BoardTeal, trackColor = Color.Transparent)
                }
                TaskStatus.RUNNING -> LiveLine(task.progress ?: "Working", BoardTeal)
                TaskStatus.WAITING -> LiveLine(task.progress ?: "Mike asks you something in the task's chat", BoardAmber)
                TaskStatus.REVIEW -> ResultBlock(task.result ?: task.progress ?: "Finished")
                TaskStatus.FAILED -> Text("Could not finish: ${task.progress ?: "unknown error"}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                TaskStatus.STOPPED -> Text("You stopped this. What was already done was not undone.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TaskStatus.UNKNOWN -> Text(task.progress ?: "It is unclear whether this finished. Check its chat first.",
                    style = MaterialTheme.typography.bodyMedium, color = BoardAmber)
                else -> Unit
            }
            CardActions(task, desk, actions)
        }
    }
}

@Composable
private fun MetaRow(task: MikeTask) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(sourceIcon(task), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(sourceLine(task), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (task.category == TaskCategory.TASK) {
            val (icon, label) = placeLine(task)
            Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposedBody(task: MikeTask) {
    TaskContent(task.why, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
    if (task.category == TaskCategory.TASK) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BoardTeal.copy(alpha = 0.07f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(taskText("Mike's plan", "התוכנית של מייק"), style = MaterialTheme.typography.labelMedium, color = BoardTeal)
            TaskContent(task.plan, MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    } else {
        TaskContent(task.plan, MaterialTheme.typography.bodyMedium, maxLines = 3)
    }
    val tags = buildList {
        task.requestedBy?.let { add(Icons.Outlined.Person to taskText("$it asked, not you", "בקשה של $it, לא שלך")) }
        task.sideEffects.firstOrNull()?.let { add(Icons.AutoMirrored.Outlined.Send to effectLabel(it)) }
        if (task.category == TaskCategory.SETTINGS) add(Icons.Outlined.Tune to taskText("Changes a setting", "שינוי הגדרה"))
    }.take(2)
    if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { (icon, label) -> Tag(icon, label) }
    }
    if (task.partialContext) {
        Text(taskText("Some context is missing · Open for details", "חלק מההקשר חסר · לפרטים נוספים"),
            style = MaterialTheme.typography.labelMedium, color = BoardAmber)
    }
    task.snoozedUntil?.takeIf { task.status == TaskStatus.SNOOZED }?.let {
        Text(taskText("Not now · back ${shortTime(it)}", "לא עכשיו · יחזור ב־${shortTime(it)}"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tag(icon: ImageVector, label: String) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, ControlLine.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (icon == Icons.AutoMirrored.Outlined.Send) BoardAmber else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LiveLine(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ResultBlock(text: String, expanded: Boolean = false) {
    Surface(color = ResultTint, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(taskText("What was done", "מה נעשה"), style = MaterialTheme.typography.labelMedium, color = BoardTeal, fontWeight = FontWeight.Bold)
            TaskContent(text, MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardActions(task: MikeTask, desk: TaskDeskState, actions: AgentUiActions, draftNote: String? = task.note) {
    var reasons by rememberSaveable(task.id) { mutableStateOf(false) }
    var follow by rememberSaveable(task.id) { mutableStateOf(false) }
    var followText by rememberSaveable(task.id) { mutableStateOf("") }
    val chat = task.execution?.sessionId
    val decide = { choice: String -> actions.onTaskDecide(task.id, choice, draftNote, null) }
    if (reasons) {
        Text(taskText("Why not?", "למה זה לא מתאים?"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(DismissReason.ALREADY_DONE to taskText("Already done", "כבר בוצע"), DismissReason.NOT_NOW to taskText("Not now", "לא עכשיו"), DismissReason.WRONG to taskText("Wrong", "לא נכון"),
                DismissReason.NEVER_THIS_KIND to taskText("Don't suggest this kind", "לא להציע כאלה")).forEach { (reason, label) ->
                OutlinedButton(onClick = { reasons = false; actions.onTaskDecide(task.id, "dismiss", draftNote, reason) },
                    border = BorderStroke(1.dp, ControlLine)) { Text(label) }
            }
            TextButton(onClick = { reasons = false; actions.onTaskDecide(task.id, "dismiss", draftNote, null) }) { Text(taskText("Skip", "דילוג")) }
        }
        return
    }
    if (follow) {
        OutlinedTextField(followText, { followText = it }, Modifier.fillMaxWidth(), label = { Text(taskText("What should Mike do next?", "מה מייק צריך לעשות עכשיו?")) }, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { follow = false; actions.onTaskDecide(task.id, "continue", followText, null); followText = "" },
                enabled = followText.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(taskText("Send", "שליחה")) }
            TextButton(onClick = { follow = false }) { Text(taskText("Cancel", "ביטול")) }
        }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (task.status) {
            TaskStatus.PROPOSED, TaskStatus.SNOOZED -> when (task.category) {
                TaskCategory.TASK -> {
                    MikeAction(taskText("Mike, do it", "מייק, תבצע")) { decide("do") }
                    TonalAction(taskText("I'll do it", "אני אבצע")) { decide("mine") }
                    IconButton(onClick = { reasons = true }) { Icon(Icons.Outlined.Close, taskText("Not useful", "לא רלוונטי")) }
                }
                TaskCategory.SETTINGS -> {
                    MikeAction(taskText("Apply", "החלת השינוי")) { decide("do") }
                    TonalAction(taskText("No thanks", "לא תודה")) { actions.onTaskDecide(task.id, "dismiss", null, DismissReason.WRONG) }
                }
                TaskCategory.REPORT -> {
                    PrimaryAction(taskText("Keep", "שמירת השינויים")) { decide("done") }
                    if (task.action != null) OutlinedButton(onClick = { decide("do") }, border = BorderStroke(1.dp, ControlLine)) { Text(taskText("Undo changes", "ביטול השינויים")) }
                }
            }
            TaskStatus.APPROVED -> Unit
            TaskStatus.RUNNING -> {
                chat?.let { TonalAction(taskText("Open chat", "פתיחת השיחה")) { actions.onOpenBoardChat(it) } }
                TextButton(onClick = { decide("stop") }) { Text(taskText("Stop", "עצירה")) }
            }
            TaskStatus.WAITING -> {
                chat?.let { PrimaryAction(taskText("Answer in chat", "מענה בשיחה")) { actions.onOpenBoardChat(it) } }
                TextButton(onClick = { decide("stop") }) { Text(taskText("Stop", "עצירה")) }
            }
            TaskStatus.REVIEW -> {
                PrimaryAction(taskText("Done", "בוצע")) { decide("done") }
                TonalAction(taskText("Continue", "המשך טיפול")) { follow = true }
                chat?.let { TextButton(onClick = { actions.onOpenBoardChat(it) }) { Text(taskText("Open chat", "פתיחת השיחה")) } }
            }
            TaskStatus.FAILED, TaskStatus.STOPPED -> {
                PrimaryAction(taskText("Try again", "ניסיון נוסף")) { decide("do") }
                TonalAction(taskText("I'll do it", "אני אבצע")) { decide("mine") }
                IconButton(onClick = { reasons = true }) { Icon(Icons.Outlined.Close, taskText("Close", "סגירה")) }
            }
            TaskStatus.UNKNOWN -> {
                chat?.let { PrimaryAction(taskText("Check in chat", "בדיקה בשיחה")) { actions.onOpenBoardChat(it) } }
                TonalAction(taskText("It's done", "זה כבר בוצע")) { decide("done") }
            }
            else -> Unit
        }
    }
}

@Composable
private fun MikeAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF19584F), contentColor = Color(0xFFCCFFF4))) {
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TonalAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(label)
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun YoursRow(task: MikeTask, actions: AgentUiActions, open: () -> Unit) {
    Surface(onClick = open, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.onTaskDecide(task.id, "done", null, null) }) {
                Icon(Icons.Outlined.CheckCircle, "Mark done", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                task.requestedBy?.let { Text("For $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun HistoryRow(task: MikeTask, actions: AgentUiActions, open: () -> Unit) {
    val line = when (task.status) {
        TaskStatus.DONE -> "Done · ${shortTime(task.closedAt ?: task.updatedAt)}"
        TaskStatus.DISMISSED -> "Dismissed" + (task.dismissReason?.let { ": " + it.wire.replace('_', ' ') } ?: "")
        TaskStatus.EXPIRED -> "Expired · was useful until ${task.usefulUntil?.let(::shortTime) ?: "then"}"
        TaskStatus.LOOKS_RESOLVED -> "Looks resolved: ${task.resolvedEvidence.orEmpty()}"
        TaskStatus.SNOOZED -> "Not now · back ${task.snoozedUntil?.let(::shortTime) ?: "later"}"
        else -> task.status.wire
    }
    Surface(onClick = open, color = Color.Transparent, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            if (task.status == TaskStatus.LOOKS_RESOLVED || task.status == TaskStatus.DISMISSED || task.status == TaskStatus.EXPIRED) {
                TextButton(onClick = { actions.onTaskDecide(task.id, "restore", null, null) }) { Text(taskText("Restore", "החזרה ללוח")) }
            }
            if (task.status == TaskStatus.SNOOZED) TextButton(onClick = { actions.onTaskDecide(task.id, "wake", null, null) }) { Text(taskText("Return now", "להחזיר עכשיו")) }
        }
    }
}

// ----- Detail -----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskDetail(task: MikeTask, desk: TaskDeskState, actions: AgentUiActions) {
    // Keep the draft with the detail page and pass it with the decision. Requiring
    // a separate Save tap used to silently discard edits when starting the work.
    var note by rememberSaveable(task.id) { mutableStateOf(task.note.orEmpty()) }
    MetaRow(task)
    TaskContent(task.title, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Tag(Icons.Outlined.Notifications, statusLabel(task))
        task.requestedBy?.let { Tag(Icons.Outlined.Person, taskText("$it asked, not you", "בקשה של $it, לא שלך")) }
        task.assignee?.let { id -> desk.members.firstOrNull { it.id == id } }?.let { Tag(Icons.Outlined.Groups, it.name) }
    }
    DetailBlock(taskText("Why now", "למה עכשיו")) {
        TaskContent(task.why, MaterialTheme.typography.bodyMedium)
        task.usefulUntil?.let { Text(taskText("Useful until ${shortTime(it)}", "רלוונטי עד ${shortTime(it)}"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    DetailBlock(if (task.category == TaskCategory.TASK) taskText("What Mike would do", "מה מייק יעשה") else taskText("What this changes", "מה ישתנה")) {
        TaskContent(task.plan, MaterialTheme.typography.bodyMedium)
        task.deliverable?.let { TaskContent(it, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (task.sideEffects.isNotEmpty()) DetailBlock(taskText("What would actually happen", "פעולות שדורשות תשומת לב")) {
        task.sideEffects.forEach { effect ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp), tint = BoardAmber)
                Text(effectLabel(effect), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(taskText("Only after your approval in the task's chat. Approving the task does not do this by itself.", "פעולות אלה עדיין כפופות לאישורים הרגילים בשיחת המשימה."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    task.result?.let { DetailBlock(taskText("Result", "תוצאה")) { ResultBlock(it, expanded = true) } }
    task.commitment?.let { follow -> DetailBlock(taskText("Follow-up", "מעקב")) {
        Text(taskText("Owner: ${if(follow.owner == "user") "you" else "Mike"} · Next check: ${shortTime(follow.nextCheckAt)}",
            "באחריות ${if(follow.owner == "user") "שלך" else "מייק"} · בדיקה חוזרת: ${shortTime(follow.nextCheckAt)}"), style = MaterialTheme.typography.bodyMedium)
        follow.deadlineAt?.let { Text(taskText("Deadline: ${shortTime(it)}", "מועד אחרון: ${shortTime(it)}")) }
        follow.completionEvidence?.let { TaskContent(taskText("Looks complete—please confirm: $it", "נראה שהושלם — לאישורך: $it"), MaterialTheme.typography.bodyMedium) }
        Text(taskText("Ask the Manager to change the follow-up time. A check never starts work.",
            "אפשר לבקש מהמנהל לשנות את מועד הבדיקה. מעקב אינו מפעיל ביצוע."), style = MaterialTheme.typography.bodySmall)
    } }
    task.briefing?.deliveredAt?.let { Text(taskText("Briefing saved in Manager chat · ${shortTime(it)}", "התדריך נשמר בצ׳אט המנהל · ${shortTime(it)}"),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (task.category == TaskCategory.TASK && (task.status.undecided || task.status == TaskStatus.MINE)) {
        DetailBlock(taskText("Instructions for Mike", "הנחיות למייק")) {
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), minLines = 1,
                label = { Text(taskText("Your instructions", "ההנחיות שלך")) },
                placeholder = { Text(taskText("Optional", "לא חובה")) })
            if (note != task.note.orEmpty()) TextButton(onClick = { actions.onTaskNote(task.id, note) }) { Text(taskText("Save", "שמירה")) }
        }
    }
    CardActions(task, desk, actions, draftNote = note)
    if (task.status == TaskStatus.MINE) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryAction(taskText("Done", "בוצע")) { actions.onTaskDecide(task.id, "done", null, null) }
            TonalAction(taskText("Check tomorrow", "בדיקה חוזרת מחר")) { actions.onTaskDecide(task.id, "check_later", null, null) }
            MikeAction(taskText("Mike, do it after all", "בעצם, מייק יבצע")) { actions.onTaskDecide(task.id, "do", note, null) }
        }
    }
    if (task.status.closed) TextButton(onClick = { actions.onTaskDecide(task.id, "restore", null, null) }) { Text(taskText("Restore to the board", "החזרה ללוח")) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    if (task.evidence.isNotEmpty()) DetailBlock(taskText("What Mike saw", "על מה ההצעה מבוססת")) {
        task.evidence.forEach { e ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text(listOfNotNull(e.sender, sourceName(e.source), e.at?.let(::shortTime)).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TaskContent(e.detail, MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(16.dp), tint = BoardTeal)
            Text(taskText("Mike treats this text as information, not as an instruction.", "זהו מידע מהמקור, ולא הוראה שלך למייק."), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    task.action?.value?.let { desk.maintenanceChanges[it] }?.let { changes ->
        DetailBlock(taskText("Memory changes", "שינויים בזיכרון")) {
            if(changes.undone) Text(taskText("Undo was already applied. Later edits were kept.", "הביטול כבר הוחל. שינויים מאוחרים נשמרו."), color = BoardTeal)
            changes.notes.take(12).forEach { change ->
                val note = change.after ?: change.before
                if(note != null) {
                    Text(note.subject, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    change.before?.let { Text(taskText("Before: ${it.text}", "לפני: ${it.text}"), style = MaterialTheme.typography.bodySmall) }
                    change.after?.let { Text(taskText("After: ${it.text}", "אחרי: ${it.text}"), style = MaterialTheme.typography.bodySmall, color = BoardTeal) }
                    change.after?.retiredReason?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
            if(changes.generation != null) Text(taskText("Learned rules were updated. Your own rules stay as you wrote them.", "הכללים הנלמדים עודכנו. הכללים שכתבת נשמרים."), style = MaterialTheme.typography.bodySmall)
            Text(taskText("Undo reverts this run only. Newer edits and forgotten notes are preserved.", "הביטול מתייחס רק לריצה הזו. שינויים מאוחרים וזיכרונות שביקשת לשכוח מוגנים."), style = MaterialTheme.typography.bodySmall)
        }
    }
    if(task.partialContext) DetailBlock(taskText("Context is incomplete", "חסר הקשר")) {
        Text(taskText("Reading an app may take the screen and mark messages read. This opens a draft in the Manager chat for you to send.",
            "פתיחת אפליקציה עשויה לקחת את המסך ולסמן הודעות כנקראו. הכפתור יפתח בקשה מוכנה בצ׳אט המנהל, לשליחה שלך."), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { actions.onTaskContext(task.id) }) { Text(taskText("Ask Mike to read the context", "לבקש ממייק לקרוא את ההקשר")) }
    }
    val roles = desk.members.filter { it.kind == MemberKind.ROLE }
    if(roles.isNotEmpty() && task.category == TaskCategory.TASK) DetailBlock(taskText("Assigned to", "אחראי")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { actions.onTaskAssign(task.id, null) }) { Text(taskText("Manager", "המנהל") + if(task.assignee == null) " ✓" else "") }
            roles.forEach { role -> TextButton(onClick = { actions.onTaskAssign(task.id, role.id) }) { Text(role.name + if(task.assignee == role.id) " ✓" else "") } }
        }
    }
    val history = desk.events.filter { it.taskId == task.id }.sortedByDescending { it.revision }
    if(history.isNotEmpty()) DetailBlock(taskText("Decisions and activity", "החלטות ופעילות")) {
        history.take(12).forEach { event ->
            val action = event.decision?.choice?.let { choice -> when(choice) {
                "do" -> taskText("Mike was asked to do it", "מייק התבקש לבצע")
                "mine" -> taskText("You took it", "לקחת לטיפולך")
                "done" -> taskText("Marked done", "סומן כבוצע")
                "dismiss" -> taskText("Dismissed", "נדחה")
                else -> choice
            } } ?: when(event.kind) { "created" -> taskText("Proposed", "הוצע"); "note" -> taskText("Instructions changed", "ההנחיות עודכנו"); "outcome" -> taskText("Execution update", "עדכון ביצוע"); else -> taskText("Updated", "עודכן") }
            Text("${shortTime(event.at)} · $action" + event.note?.let { "\n$it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
    }
    task.progress?.takeIf { task.status.executing && task.result == null }?.let { DetailBlock(taskText("Now", "עכשיו")) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
}

private fun statusLabel(task: MikeTask): String = when (task.status) {
    TaskStatus.PROPOSED -> taskText("Waiting for your decision", "מחכה להחלטה שלך")
    TaskStatus.APPROVED -> taskText("Starting", "מתחיל")
    TaskStatus.RUNNING -> taskText("Mike is on it", "מייק מטפל בזה")
    TaskStatus.WAITING -> taskText("Waiting for your answer", "מחכה לתשובה שלך")
    TaskStatus.REVIEW -> taskText("To check", "מוכן לבדיקה")
    TaskStatus.DONE -> taskText("Done", "בוצע")
    TaskStatus.MINE -> taskText("Yours", "בטיפול שלך")
    TaskStatus.DISMISSED -> taskText("Dismissed", "נדחה")
    TaskStatus.SNOOZED -> taskText("Not now", "לא עכשיו")
    TaskStatus.EXPIRED -> taskText("Expired", "כבר לא רלוונטי")
    TaskStatus.LOOKS_RESOLVED -> taskText("Looks resolved", "נראה שנפתר")
    TaskStatus.FAILED -> taskText("Could not finish", "לא הצליח לסיים")
    TaskStatus.STOPPED -> taskText("Stopped", "נעצר")
    TaskStatus.UNKNOWN -> taskText("Outcome unknown", "התוצאה לא ידועה")
}

@Composable
private fun DetailBlock(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

// ----- Team -------------------------------------------------------------------------

internal fun memberMood(member: TeamMember, desk: TaskDeskState, busy: Boolean): EyeMood {
    if (member.kind == MemberKind.MANAGER) return managerMood(desk, busy)
    val mine = desk.tasks.filter { it.assignee == member.id }
    return when {
        member.kind.backstage && !desk.settings.discoveryOn -> EyeMood.PAUSED
        busy -> if (member.kind.backstage) EyeMood.THINKING else EyeMood.WORKING
        mine.any { it.status == TaskStatus.WAITING || it.status == TaskStatus.PROPOSED } -> EyeMood.NEEDS
        mine.any { it.status == TaskStatus.RUNNING } -> EyeMood.WORKING
        mine.any { it.status == TaskStatus.REVIEW } -> EyeMood.DONE
        else -> EyeMood.IDLE
    }
}

private fun memberBusy(member: TeamMember, desk: TaskDeskState, state: AgentUiState): Boolean =
    desk.activity.containsKey(member.id) || member.sessionId?.let { state.runs[it]?.active } == true

@Composable
private fun TeamPage(state: AgentUiState, actions: AgentUiActions, open: (String) -> Unit) {
    val desk = state.taskDesk
    val manager = desk.members.firstOrNull { it.kind == MemberKind.MANAGER }
    manager?.let { MemberRow(it, desk, state, big = true) { desk.managerSessionId?.let(actions.onOpenBoardChat) } }
    val backstage = desk.members.filter { it.kind.backstage }
    if (backstage.isNotEmpty()) {
        SectionLabel(taskText("Backstage", "מאחורי הקלעים"), backstage.size, MaterialTheme.colorScheme.onSurfaceVariant)
        backstage.forEach { member -> MemberRow(member, desk, state) { open(member.id) } }
    }
    val roles = desk.members.filter { it.kind == MemberKind.ROLE }
    SectionLabel(taskText("By area", "תחומי עבודה"), roles.size, MaterialTheme.colorScheme.onSurfaceVariant)
    if (roles.isEmpty()) Text(taskText("Mike opens an area when similar tasks keep coming, like Meetings after a few preparations.", "כשמצטברות משימות דומות, מייק יכול ליצור עבורן תפקיד ייעודי בצוות."),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    roles.forEach { member -> MemberRow(member, desk, state) { open(member.id) } }
}

@Composable
private fun MemberRow(member: TeamMember, desk: TaskDeskState, state: AgentUiState?, big: Boolean = false, onClick: () -> Unit) {
    val busy = state?.let { memberBusy(member, desk, it) } ?: false
    val mood = memberMood(member, desk, busy)
    val tint = if (member.kind == MemberKind.MANAGER) null else memberColor(member.hue)
    val status = memberStatus(member, desk, busy)
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MikeEye(mood, Modifier.size(if (big) 64.dp else 48.dp), tint = tint, animate = big || busy)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (member.kind == MemberKind.MANAGER) taskText("Mike · Manager", "מייק · המנהל") else member.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(member.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text(status.first, style = MaterialTheme.typography.labelMedium, color = status.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ForwardChevron()
        }
    }
}

@Composable
private fun memberStatus(member: TeamMember, desk: TaskDeskState, busy: Boolean): Pair<String, Color> {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    desk.activity[member.id]?.let { return it to BoardTeal }
    if (busy) return taskText("Working now", "עובד עכשיו") to BoardTeal
    val mine = desk.tasks.filter { it.assignee == member.id }
    return when {
        member.kind.backstage && !desk.settings.discoveryOn -> taskText("Paused · discovery is off", "מושהה · החיפוש כבוי") to BoardAmber
        desk.error != null && member.kind.backstage -> taskText("Needs attention", "דרושה בדיקה") to BoardAmber
        mine.any { it.status == TaskStatus.WAITING } -> taskText("Asks you something", "מחכה לתשובה שלך") to BoardAmber
        mine.count { it.status == TaskStatus.RUNNING }.let { it > 0 } -> taskText("Working on ${mine.count { it.status == TaskStatus.RUNNING }}", "מטפל ב־${mine.count { it.status == TaskStatus.RUNNING }} משימות") to BoardTeal
        else -> (member.journal.lines().lastOrNull { it.isNotBlank() }?.let { taskText("Last: $it", "לאחרונה: $it") } ?: taskText("Resting", "פנוי")) to muted
    }
}

@Composable
private fun MemberPage(member: TeamMember, state: AgentUiState, actions: AgentUiActions, openCard: (String) -> Unit) {
    val desk = state.taskDesk
    val busy = memberBusy(member, desk, state)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MikeEye(memberMood(member, desk, busy), Modifier.size(150.dp), tint = memberColor(member.hue))
        Text(member.role, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val status = memberStatus(member, desk, busy)
        Text(status.first, style = MaterialTheme.typography.labelLarge, color = status.second)
    }
    var editing by rememberSaveable(member.id) { mutableStateOf(false) }
    var name by rememberSaveable(member.id) { mutableStateOf(member.name) }
    var role by rememberSaveable(member.id) { mutableStateOf(member.role) }
    TextButton(onClick = { editing = !editing }) { Text(taskText("Edit this member", "עריכת חבר הצוות")) }
    if(editing) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(taskText("Name", "שם")) })
        OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text(taskText("Role", "תפקיד")) })
        Button(onClick = { actions.onTaskRenameMember(member.id, name, role); editing = false }) { Text(taskText("Save", "שמירה")) }
        if(member.kind == MemberKind.ROLE) {
            state.taskDesk.members.filter { it.kind == MemberKind.ROLE && it.id != member.id }.forEach { target ->
                TextButton(onClick = { actions.onTaskMergeMember(member.id, target.id) }) { Text(taskText("Merge into ${target.name}", "איחוד עם ${target.name}")) }
            }
            TextButton(onClick = { actions.onTaskRemoveMember(member.id) }) { Text(taskText("Remove role · keep chats", "הסרת תפקיד · השיחות יישמרו"), color = MaterialTheme.colorScheme.error) }
        }
    }
    val journal = member.journal.lines().filter { it.isNotBlank() }.reversed()
    SectionLabel(taskText("What ${member.name} did", "הפעילות של ${member.name}"), journal.size, MaterialTheme.colorScheme.onSurfaceVariant)
    if (journal.isEmpty()) Text(taskText("Nothing yet.", "עדיין אין פעילות."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    journal.take(12).forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(memberColor(member.hue)))
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
    val cards = desk.tasks.filter { it.assignee == member.id }.take(10)
    if (cards.isNotEmpty()) {
        SectionLabel(taskText("Cards", "משימות"), cards.size, MaterialTheme.colorScheme.onSurfaceVariant)
        cards.forEach { task -> HistoryRow(task, actions) { openCard(task.id) } }
    }
    member.sessionId?.let { chat ->
        Button(onClick = { actions.onOpenBoardChat(chat) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(taskText("Message ${member.name}", "לדבר עם ${member.name}"))
        }
        Text(
            if (member.kind.backstage) taskText("${member.name} still only reads and proposes when you talk to it.", "גם בשיחה ישירה, התפקיד הזה קורא ומציע בלבד.")
            else taskText("What you write here counts as your own words for ${member.name}'s cards.", "כאן אפשר לתת הנחיות ישירות למשימות של ${member.name}."),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----- Sources and memory -----------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesPage(state: AgentUiState, actions: AgentUiActions, openMemory: () -> Unit) {
    val settings = state.taskDesk.settings
    Text(taskText("Mike reads these sources to find tasks. Processed input is removed after a successful pass; useful memory stays.", "מייק מחפש משימות במקורות האלה. אחרי עיבוד מוצלח הטקסט שנאסף נמחק, והמידע החשוב נשמר בזיכרון קצר."),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwitchRow(Icons.Outlined.AutoAwesome, taskText("Find tasks in the background", "חיפוש משימות ברקע"), taskText("Collects continuously; thinks in small batches", "איסוף רציף וחשיבה בסבבים קצרים"), settings.discoveryOn) {
        actions.onTaskDiscovery(it)
    }
    if (!state.automations.notificationAccess) {
        OutlinedButton(onClick = actions.onOpenNotificationAccess, border = BorderStroke(1.dp, ControlLine), modifier = Modifier.fillMaxWidth()) {
            Text(taskText("Allow notification access", "לאפשר גישה להתראות"))
        }
    }
    SwitchRow(Icons.Outlined.Groups, taskText("Group conversations", "שיחות קבוצתיות"), taskText("Include messages from groups", "לכלול גם הודעות מקבוצות"), settings.includeGroups) { on ->
        actions.onTaskSettings { it.copy(includeGroups = on) }
    }
    SwitchRow(Icons.Outlined.ChatBubbleOutline, taskText("Your chats with Mike", "השיחות שלך עם מייק"), taskText("After a chat is quiet for 15 minutes", "אחרי שהשיחה שקטה במשך רבע שעה"), settings.readChats) { on ->
        actions.onTaskSettings { it.copy(readChats = on) }
    }
    SwitchRow(Icons.Outlined.CalendarToday, taskText("Calendar", "יומן"), taskText("Changes and a check of the next 48 hours", "שינויים ובדיקה של היומיים הקרובים"), settings.readCalendar) { on ->
        actions.onTaskSettings { it.copy(readCalendar = on) }
    }
    if (settings.readCalendar && !state.taskDesk.calendarGranted) {
        OutlinedButton(onClick = actions.onTaskCalendarPermission, modifier = Modifier.fillMaxWidth()) {
            Text(taskText("Allow calendar access", "לאפשר קריאת היומן"))
        }
    }
    Row(Modifier.fillMaxWidth().border(1.dp, ControlLine.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(taskText("Verification codes and payment confirmations are filtered before saving input for discovery.", "קודי אימות ואישורי תשלום מסוננים לפני השמירה."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionLabel(taskText("Models", "מודלים"), 3, MaterialTheme.colorScheme.onSurfaceVariant)
    ModelChoiceRow(taskText("Discovery passes", "חיפוש משימות"), settings.passEngine, settings.passModel, state.modelCatalog) { engine, id ->
        actions.onTaskSettings { it.copy(passModel = id, passEngine = engine, passEffort = null) }
    }
    ModelChoiceRow(taskText("Nightly review and memory", "סקירה לילית וניהול הזיכרון"), settings.reviewEngine, settings.reviewModel, state.modelCatalog) { engine, id ->
        actions.onTaskSettings { it.copy(reviewModel = id, reviewEngine = engine, reviewEffort = null) }
    }
    ModelChoiceRow(taskText("Approved task execution", "ביצוע משימות שאישרת"), settings.executionEngine ?: settings.passEngine,
        settings.executionModel, state.modelCatalog, inherited = settings.executionEngine == null) { engine, id ->
        actions.onTaskSettings { it.copy(executionEngine = engine, executionModel = id, executionEffort = null) }
    }
    if(settings.executionEngine != null) TextButton(onClick = {
        actions.onTaskSettings { it.copy(executionEngine = null, executionModel = null, executionEffort = null) }
    }) { Text(taskText("Use the Manager's model", "שימוש במודל של המנהל")) }
    state.modelCatalog.firstOrNull { it.engine == settings.executionEngine && it.id == settings.executionModel }
        ?.reasoningEfforts?.takeIf { it.isNotEmpty() }?.let { efforts ->
            Text(taskText("Execution thinking level", "רמת חשיבה לביצוע"), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                efforts.forEach { effort -> TextButton(onClick = { actions.onTaskSettings { it.copy(executionEffort = effort.value) } }) {
                    Text(effort.value + if(settings.executionEffort == effort.value) " ✓" else "")
                } }
            }
        }
    Text(taskText("Without an execution override, tasks use the Manager's model and thinking level. Follow-ups appear in Manager chat; notifications are quiet and optional in Android settings.",
        "ללא בחירת מודל לביצוע, המשימות משתמשות במודל וברמת החשיבה של המנהל. תדריכים ומעקבים מופיעים בצ׳אט המנהל; ההתראות שקטות וניתן לכבות אותן בהגדרות Android."), style = MaterialTheme.typography.bodySmall)
    Text(taskText("Automatic Manager reviews use the discovery model, up to four times a day. Nightly work uses the review model.",
        "סקירות המנהל משתמשות במודל החיפוש, עד ארבע פעמים ביום. עבודת הלילה משתמשת במודל הסקירה."), style = MaterialTheme.typography.bodySmall)
    Text(taskText("Discovery pass limit per day", "מגבלת סבבי חיפוש ביום"), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(4, 12, 24, 48).forEach { cap -> TextButton(onClick = { actions.onTaskSettings { it.copy(dailyPassCap = cap) } }) {
            Text("$cap" + if(settings.dailyPassCap == cap) " ✓" else "")
        } }
    }
    Text(taskText("Unprocessed input is retained for up to 72 hours. This limit counts jobs, not a price guarantee.",
        "מידע שלא עובד נשמר עד 72 שעות. המגבלה סופרת סבבים, ואינה מבטיחה מחיר כספי."), style = MaterialTheme.typography.bodySmall)

    if (state.taskDesk.sourceApps.isNotEmpty()) {
        SectionLabel(taskText("Observed apps", "אפליקציות שנאספו מהן התראות"), state.taskDesk.sourceApps.size, MaterialTheme.colorScheme.onSurfaceVariant)
        state.taskDesk.sourceApps.toSortedMap().forEach { (app, label) ->
            SwitchRow(Icons.Outlined.Notifications, label, app, app !in settings.excludedApps) { on ->
                actions.onTaskSettings { it.copy(excludedApps = if (on) it.excludedApps - app else it.excludedApps + app) }
            }
        }
    }
    if (settings.excludedApps.isNotEmpty() || settings.excludedConversations.isNotEmpty()) {
        SectionLabel(taskText("Not read", "מקורות מוחרגים"), settings.excludedApps.size + settings.excludedConversations.size, MaterialTheme.colorScheme.onSurfaceVariant)
        settings.excludedApps.sorted().forEach { app ->
            RemovableRow(app) { actions.onTaskSettings { it.copy(excludedApps = it.excludedApps - app) } }
        }
        settings.excludedConversations.sorted().forEach { conversation ->
            RemovableRow(conversation.replace("|", " · ")) { actions.onTaskSettings { it.copy(excludedConversations = it.excludedConversations - conversation) } }
        }
    }
    if (settings.suppressed.isNotEmpty()) {
        SectionLabel(taskText("Never suggest", "לא להציע שוב"), settings.suppressed.size, MaterialTheme.colorScheme.onSurfaceVariant)
        settings.suppressed.reversed().take(30).forEach { entry ->
            RemovableRow(entry.title ?: entry.kind ?: entry.topicKey.orEmpty()) { actions.onTaskLiftSuppression(entry) }
        }
    }

    SectionLabel(taskText("Your rules", "הכללים שלך"), 0, MaterialTheme.colorScheme.onSurfaceVariant)
    var rule by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(rule, { rule = it }, Modifier.fillMaxWidth(), placeholder = { Text(taskText("For example: never suggest delivery tracking", "למשל: לא להציע מעקב משלוחים")) }, minLines = 1)
    if (rule.isNotBlank()) TextButton(onClick = { actions.onTaskAddRule(rule); rule = "" }) { Text(taskText("Add rule", "הוספת כלל")) }
    Text(taskText("Your rules rank above learned rules. The nightly review cannot change them.", "הכללים שלך קודמים לכללים הנלמדים. הסקירה הלילית לא משנה אותם."),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionLabel(taskText("Collected now", "ממתין לעיבוד"), state.taskDesk.pendingSignals, MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = openMemory) { Text(taskText("Memory notes", "זיכרון") + " (${state.taskDesk.notes.count { it.retiredAt == null && it.supersededBy == null }})") }
    OutlinedButton(onClick = actions.onTaskForgetCollected, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()) {
        Text(taskText("Delete collected input", "מחיקת מה שנאסף"), color = MaterialTheme.colorScheme.error)
    }
    Text(taskText("Cards and memory stay. This removes only input waiting for the next pass.", "המשימות והזיכרון נשארים. נמחק רק מידע שממתין לסבב הבא."),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedThumbColor = BoardTeal))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelChoiceRow(label: String, engine: EngineKind, current: String?, options: List<AgentModel>, inherited: Boolean = false, choose: (EngineKind, String?) -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 56.dp).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 15.sp)
            val name = options.firstOrNull { it.id == current && it.engine == engine }?.displayName ?: current ?: taskText("Default", "ברירת מחדל")
            Text(if(inherited) taskText("Manager's model and thinking level", "המודל ורמת החשיבה של המנהל")
                else "${engine.name.lowercase().replaceFirstChar { it.uppercase() }} · $name", color = BoardTeal, fontSize = 14.sp)
        }
        if (open) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EngineKind.entries.forEach { kind ->
                OutlinedButton(onClick = { open = false; choose(kind, null) }, border = BorderStroke(1.dp, ControlLine)) {
                    Text("${kind.name.lowercase().replaceFirstChar { it.uppercase() }} · " + taskText("Default", "ברירת מחדל"))
                }
            }
            options.distinctBy { it.engine to it.id }.forEach { model ->
                OutlinedButton(onClick = { open = false; choose(model.engine, model.id) },
                    border = BorderStroke(1.dp, if (model.id == current && model.engine == engine) BoardTeal else ControlLine)) {
                    Text("${model.displayName} · ${model.engine.name.lowercase().replaceFirstChar { it.uppercase() }}")
                }
            }
            if (options.isEmpty()) Text(taskText("Open a chat to load the model list.", "לרשימת המודלים המלאה, יש לפתוח שיחה במנוע הרצוי."), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RemovableRow(label: String, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = remove) { Icon(Icons.Outlined.Close, "Remove $label") }
    }
}

@Composable
private fun MemoryPage(desk: TaskDeskState, actions: AgentUiActions) {
    val live = desk.notes.filter { it.retiredAt == null && it.supersededBy == null }
    val chars = live.sumOf { it.text.length + (it.cue?.length ?: 0) }
    val pressure = (chars / dev.androidagent.core.MemoryBook.BUDGET_CHARS.toFloat()).coerceIn(0f, 1f)
    Text(taskText("Short memory notes. The Librarian reviews and compacts them, keeping what is useful.", "הזיכרון הקצר של מייק. הספרן מסדר ומכווץ אותו, ושומר את מה שעדיין מועיל."),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LinearProgressIndicator(progress = { pressure }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = if (pressure > 0.85f) BoardAmber else BoardTeal, trackColor = MaterialTheme.colorScheme.surfaceVariant)
    Text(taskText("${live.size} notes · ${(pressure * 100).toInt()}% of the memory budget · ${desk.notes.count { it.retiredAt != null }} in the trash",
        "${live.size} זיכרונות · ${(pressure * 100).toInt()}% מתקציב הזיכרון · ${desk.notes.count { it.retiredAt != null }} בפח"),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (live.isEmpty()) Text(taskText("No notes yet.", "עדיין אין זיכרונות."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    live.groupBy { it.subject }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (subject, notes) ->
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(subject.take(1).uppercase(), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
            Text("$subject · ${notes.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { actions.onTaskForgetSubject(subject) }) { Text(taskText("Forget all", "לשכוח הכול"), color = MaterialTheme.colorScheme.error) }
        }
        notes.sortedByDescending { it.at }.forEach { NoteRow(it, actions) }
    }
}

@Composable
private fun NoteRow(note: MemoryNote, actions: AgentUiActions) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(note.text, style = MaterialTheme.typography.bodyMedium)
                note.cue?.let { Text(taskText("If: $it", "אם: $it"), style = MaterialTheme.typography.labelMedium, color = BoardTeal) }
                Text(listOfNotNull(note.kind, note.level.takeIf { it != "detail" }, note.sender, note.source, shortTime(note.at),
                    note.useCount.takeIf { it > 0 }?.let { "used $it×" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { actions.onTaskPinNote(note.id, !note.pinned) }) {
                Icon(Icons.Outlined.PushPin, if (note.pinned) taskText("Unpin", "ביטול נעיצה") else taskText("Pin", "נעיצה"), tint = if (note.pinned) BoardTeal else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { actions.onTaskForgetNote(note.id) }) { Icon(Icons.Outlined.DeleteOutline, taskText("Forget this note", "לשכוח את הזיכרון")) }
        }
    }
}
