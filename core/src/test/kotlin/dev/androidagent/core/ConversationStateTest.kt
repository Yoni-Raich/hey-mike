package dev.androidagent.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConversationStateTest {
    private val hour = 3_600_000L
    private val app = "com.whatsapp"

    private fun message(text: String, at: Long, sender: String = "Dana", key: String = "k1", conversation: String = "Dana") =
        CapturedMessage(app, conversation, sender, text, at, group = false, notificationKey = key, fromPerson = true)

    private fun row(vararg texts: String, at: Long = 1_000, group: Boolean = false, sender: String = "Dana"): TaskSignal {
        var row: TaskSignal? = null
        texts.forEachIndexed { i, text -> row = SignalRows.merge(row, message(text, at + i, sender).copy(group = group), text, at + i) }
        return row!!
    }

    @Test fun smallTalkIsLightAndRealAsksAreNot() {
        listOf("אתה בא?", "איפה אתה", "מה קורה", "ok", "חחח", "are you coming?").forEach { assertFalse(it, SignalTriage.substantive(it)) }
        listOf("להכין 330 במזומן", "תקנה חלב בדרך", "אל תשכח את הטופס", "מחר יש טיפת חלב", "https://example.com/form",
            "can you send me the file", "הפגישה נדחתה", "זה עולה 40 ₪").forEach { assertTrue(it, SignalTriage.substantive(it)) }
    }

    @Test fun lightMessageTheOwnerSawNeverReachesAModel() {
        var clock = 10_000L
        val ledger = ConversationLedger(now = { clock })
        ledger.posted(message("אתה בא?", clock))
        val light = row("אתה בא?", at = clock)
        val key = ConversationLedger.key(light)
        assertEquals(SignalTriage.Verdict.HOLD, SignalTriage.verdict(light, ledger.mark(key), clock + 60_000, quiet = false))
        clock += 120_000
        ledger.removed(app, "k1")
        assertEquals(SignalTriage.Verdict.DROP, SignalTriage.verdict(light, ledger.mark(key), clock, quiet = false))
        // A notification that comes back is unseen again.
        clock += 60_000
        ledger.posted(message("נו?", clock))
        assertEquals(SignalTriage.Verdict.HOLD, SignalTriage.verdict(light, ledger.mark(key), clock, quiet = false))
    }

    @Test fun substantiveMessagePassesEvenWhenSeenAndCarriesTheNote() {
        val clock = 10_000L
        val ledger = ConversationLedger(now = { clock })
        ledger.posted(message("תקנה חלב", clock))
        ledger.removed(app, "k1")
        val ask = row("אתה בא?", "תקנה חלב", at = clock)
        val mark = ledger.mark(ConversationLedger.key(ask))
        assertEquals(SignalTriage.Verdict.PASS, SignalTriage.verdict(ask, mark, clock, quiet = false))
        assertEquals("owner_saw", SignalTriage.note(ask, mark, clock))
    }

    @Test fun unopenedLightMessagePassesAfterHoursButNotInQuietHours() {
        val light = row("איפה אתה", at = 0)
        val late = SignalTriage.UNANSWERED_MS + 1_000
        assertEquals(SignalTriage.Verdict.HOLD, SignalTriage.verdict(light, null, hour, quiet = false))
        assertEquals(SignalTriage.Verdict.HOLD, SignalTriage.verdict(light, null, late, quiet = true))
        assertEquals(SignalTriage.Verdict.PASS, SignalTriage.verdict(light, null, late, quiet = false))
        assertEquals("unopened_3h", SignalTriage.note(light, null, late))
    }

    @Test fun lightGroupChatterAndOwnLinesAreDropped() {
        val group = row("חחח", "מי בא?", at = 0, group = true)
        assertEquals(SignalTriage.Verdict.HOLD, SignalTriage.verdict(group, null, 60_000, quiet = false))
        assertEquals(SignalTriage.Verdict.DROP, SignalTriage.verdict(group, null, SignalTriage.GROUP_SETTLE_MS + 10, quiet = false))
        val own = row("אני בדרך", at = 0, sender = ConversationLedger.OWNER)
        assertEquals(SignalTriage.Verdict.DROP, SignalTriage.verdict(own, null, 1_000, quiet = false))
    }

    @Test fun otherSourcesAreNotJudged() {
        val mail = TaskSignal("m", "email", app = "com.google.android.gm", text = "hi", at = 0, capturedAt = 0, reason = SignalRows.PERSON_MESSAGE)
        val calendar = TaskSignal("c", "calendar", text = "x", at = 0, capturedAt = 0, reason = SignalRows.CALENDAR_CHANGE)
        assertEquals(SignalTriage.Verdict.PASS, SignalTriage.verdict(mail, null, 0, quiet = false))
        assertEquals(SignalTriage.Verdict.PASS, SignalTriage.verdict(calendar, null, 0, quiet = false))
    }

    @Test fun ownReplyInTheNotificationCountsAsHandled() {
        var clock = 1_000L
        val ledger = ConversationLedger(now = { clock })
        ledger.posted(message("אתה בא?", clock))
        clock += 5_000
        ledger.posted(message("כן", clock, sender = ConversationLedger.OWNER))
        assertTrue(ledger.mark("$app|Dana")!!.handled)
        assertTrue(ledger.mark("$app|Dana")!!.handledAfter(2_000))
    }

    @Test fun ledgerSurvivesARestart() {
        val file = java.io.File.createTempFile("ledger", ".json").also { it.delete() }
        try {
            val first = ConversationLedger(file, now = { 5_000 })
            first.posted(message("אתה בא?", 5_000))
            first.removed(app, "k1")
            assertTrue(ConversationLedger(file, now = { 6_000 }).mark("$app|Dana")!!.handled)
        } finally { file.delete() }
    }

    // ----- Cards end on their own -------------------------------------------------

    private var clock = 1_000_000L
    private fun board() = TaskBoard(InMemoryTaskBoardStore(), now = { clock })
    private val chatRef = TaskSourceRef("whatsapp", app, "Dana", verified = true)
    private fun proposal(title: String, reply: Boolean = false, until: Long? = null, deadline: Long? = null) = TaskProposal(title, "why", "plan",
        evidence = listOf(TaskEvidence("whatsapp", "said so")), sourceRefs = listOf(chatRef), awaitsReply = reply, usefulUntil = until, deadlineAt = deadline)
    private suspend fun TaskBoard.make(p: TaskProposal) = (propose(p, "signal", null) as ProposeOutcome.Created).task

    @Test fun replyCardClosesWhenTheConversationIsOpened() = runTest {
        val board = board()
        val reply = board.make(proposal("Answer Dana", reply = true))
        val errand = board.make(proposal("Buy milk"))
        clock += 60_000
        board.sweep { _, _ -> false }
        assertEquals(TaskStatus.PROPOSED, board.task(reply.id)!!.status)
        board.sweep { conversation, since -> conversation == "$app|Dana" && since == reply.createdAt }
        assertEquals(TaskStatus.LOOKS_RESOLVED, board.task(reply.id)!!.status)
        assertEquals(CardLifecycle.SEEN, board.task(reply.id)!!.resolvedEvidence)
        assertEquals(TaskStatus.PROPOSED, board.task(errand.id)!!.status)
    }

    @Test fun replyCardExpiresAfterSixHoursAndASuggestionAfterTwoDays() = runTest {
        val board = board()
        val reply = board.make(proposal("Answer Dana", reply = true))
        val errand = board.make(proposal("Buy milk"))
        val dated = board.make(proposal("Prepare for the trip", deadline = clock + 5 * 24 * hour))
        clock += CardLifecycle.REPLY_LIFETIME_MS
        board.sweep()
        assertEquals(TaskStatus.EXPIRED, board.task(reply.id)!!.status)
        assertEquals(TaskStatus.PROPOSED, board.task(errand.id)!!.status)
        clock += CardLifecycle.PROPOSAL_LIFETIME_MS
        board.sweep()
        assertEquals(TaskStatus.EXPIRED, board.task(errand.id)!!.status)
        assertEquals(TaskStatus.PROPOSED, board.task(dated.id)!!.status)
    }

    @Test fun takenCardIsKeptUntilADayAfterItsReminderAndANewReminderKeepsIt() = runTest {
        val board = board()
        val a = board.make(proposal("Wash the bowl"))
        val b = board.make(proposal("Call the bank"))
        board.decide(a.id, "mine", "tap")
        board.decide(b.id, "mine", "tap")
        clock += 24 * hour + CardLifecycle.MINE_GRACE_MS - 60_000
        board.sweep()
        assertEquals(TaskStatus.MINE, board.task(a.id)!!.status)
        board.decide(b.id, "check_later", "tap", until = clock + 3 * 24 * hour)
        clock += 120_000
        board.sweep()
        assertEquals(TaskStatus.EXPIRED, board.task(a.id)!!.status)
        assertEquals(CardLifecycle.MINE_EXPIRED, board.task(a.id)!!.resolvedEvidence)
        assertEquals(TaskStatus.MINE, board.task(b.id)!!.status)
        // History, not deletion: the owner can bring it back.
        assertEquals(TaskStatus.PROPOSED, board.decide(a.id, "restore", "tap").status)
    }

    @Test fun workMikeCarriesOutAndReportsAreNeverClosedHere() {
        val base = MikeTask("t", "x", "w", "p", originKind = "signal", topicKey = "x", createdAt = 0, updatedAt = 0)
        val far = 400 * 24 * hour
        listOf(TaskStatus.RUNNING, TaskStatus.WAITING, TaskStatus.REVIEW, TaskStatus.FAILED, TaskStatus.DONE).forEach {
            assertNull(it.wire, CardLifecycle.close(base.copy(status = it), far) { _, _ -> true })
        }
        assertNull(CardLifecycle.close(base.copy(category = TaskCategory.REPORT), far) { _, _ -> true })
    }
}
