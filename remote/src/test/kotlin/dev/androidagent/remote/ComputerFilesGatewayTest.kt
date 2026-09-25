package dev.androidagent.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ComputerFilesGatewayTest {
    @Test fun aRelativeNameIsInTheChatsFolderAndAnAbsoluteOneStaysAsGiven() {
        assertEquals("C:\\src\\app\\out\\a.png", ComputerFilesGateway.onComputer("C:\\src\\app\\", "out/a.png"))
        assertEquals("D:\\pics\\a.png", ComputerFilesGateway.onComputer("C:\\src\\app", "D:\\pics\\a.png"))
        assertEquals("C:/x/a.png", ComputerFilesGateway.onComputer("C:\\src", "C:/x/a.png"))
        assertEquals("a.png", ComputerFilesGateway.fileName("D:\\pics\\a.png"))
    }

    @Test fun sftpSpellsWindowsPathsFromTheRoot() {
        assertEquals("/C:/Users/yoni/a.png", SshLink.sftpPath("C:\\Users\\yoni\\a.png"))
    }
}
