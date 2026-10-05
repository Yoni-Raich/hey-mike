package dev.androidagent.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PhoneAccountTokensTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun readsOnlyTheActiveChatgptAccessToken() {
        val auth = File(temp.root, "auth.json")
        auth.writeText("""{"auth_mode":"chatgpt","tokens":{"access_token":"short-lived","account_id":"phone-account","refresh_token":"phone-only"}}""")

        val tokens = PhoneAccountTokens.fromAuthFile(auth)

        assertEquals("short-lived", tokens.accessToken)
        assertEquals("phone-account", tokens.accountId)
        assertFalse(tokens.toString().contains("short-lived"))
        assertFalse(tokens.toString().contains("phone-only"))
    }

    @Test fun refusesMissingOrNonChatgptPhoneSignIn() {
        val auth = File(temp.root, "auth.json")
        assertThrows(IllegalStateException::class.java) { PhoneAccountTokens.fromAuthFile(auth) }
        auth.writeText("""{"auth_mode":"apiKey","tokens":{"access_token":"other","account_id":"other-account"}}""")
        assertThrows(IllegalStateException::class.java) { PhoneAccountTokens.fromAuthFile(auth) }
        auth.writeText("""{"auth_mode":"chatgpt","tokens":{"access_token":"short-lived"}}""")
        assertThrows(IllegalStateException::class.java) { PhoneAccountTokens.fromAuthFile(auth) }
    }
}
