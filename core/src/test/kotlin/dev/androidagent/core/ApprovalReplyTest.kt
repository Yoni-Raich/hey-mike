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
