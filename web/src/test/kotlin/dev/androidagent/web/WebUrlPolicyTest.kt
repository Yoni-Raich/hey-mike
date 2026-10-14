package dev.androidagent.web

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WebUrlPolicyTest {
    private val policy = WebUrlPolicy(resolve = { listOf(InetAddress.getByName("93.184.216.34")) })
    @Test fun acceptsPublicWebAndAddsScheme() {
        assertEquals(WebUrlPolicy.Decision.Allowed("https://example.com/path?q=a&b=c"), policy.check("example.com/path?q=a&b=c"))
        assertTrue(policy.check("about:blank") is WebUrlPolicy.Decision.Allowed)
    }
    @Test fun refusesNonWebSchemes() {
        listOf("file:///sdcard/a", "content://contacts/1", "javascript:alert(1)", "intent://x", "data:text/html,x").forEach {
            assertTrue(it, policy.check(it) is WebUrlPolicy.Decision.Blocked)
        }
    }
    @Test fun refusesPrivateLiteralAddresses() {
        listOf("127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.100.100.100", "[::1]", "[fc00::1]").forEach {
            assertTrue(it, policy.check("http://$it/x") is WebUrlPolicy.Decision.Blocked)
        }
    }
    @Test fun refusesLocalNamesCredentialsAndMalformedUrls() {
        listOf("http://localhost", "https://router.local", "https://api.internal", "https://u:p@example.com", "https://", "hello world").forEach {
            assertTrue(it, policy.check(it) is WebUrlPolicy.Decision.Blocked)
        }
    }
    @Test fun checksDnsAndFailsClosedWhenUnresolved() {
        assertTrue(WebUrlPolicy(resolve = { listOf(InetAddress.getByName("127.0.0.1")) }).check("https://public.example") is WebUrlPolicy.Decision.Blocked)
        assertTrue(WebUrlPolicy(resolve = { emptyList() }).check("https://public.example") is WebUrlPolicy.Decision.Blocked)
    }
    @Test fun privateNetworkOverrideIsExplicit() {
        assertTrue(WebUrlPolicy(allowPrivateNetwork = true).check("http://127.0.0.1") is WebUrlPolicy.Decision.Allowed)
        assertTrue(WebUrlPolicy(allowPrivateNetwork = true).check("file:///x") is WebUrlPolicy.Decision.Blocked)
    }
}
