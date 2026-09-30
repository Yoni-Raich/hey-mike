package dev.androidagent.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAttachmentNamesTest {
    @Test fun aNameBothWindowsAndLinuxAccept() {
        assertEquals("report 2026.pdf", RemoteHub.safeRemoteName("report 2026.pdf"))
        assertEquals("Photo 2026-09-30 15.04.11.jpg", RemoteHub.safeRemoteName("Photo 2026-09-30 15.04.11.jpg"))
        // A Hebrew name stays as it is: both systems keep Unicode names.
        assertEquals("חשבונית.pdf", RemoteHub.safeRemoteName("חשבונית.pdf"))
        // Separators and Windows' forbidden characters cannot climb out of the folder.
        assertEquals(".._.._x_y_.txt", RemoteHub.safeRemoteName("../..\\x:y?.txt"))
        assertEquals("attachment", RemoteHub.safeRemoteName(" . "))
    }

    @Test fun anAttachmentLandsInsideTheProjectInTheComputersOwnStyle() {
        val folder = ".hey-mike/attachments/20260930-150411"
        assertEquals(
            "C:\\src\\app\\.hey-mike\\attachments\\20260930-150411\\a.pdf",
            ComputerPlace.fullPath("$folder/a.pdf", "C:\\src\\app", "Pc"),
        )
        assertEquals(
            "/home/me/app/.hey-mike/attachments/20260930-150411/a.pdf",
            ComputerPlace.fullPath("$folder/a.pdf", "/home/me/app", "Server"),
        )
    }
}
