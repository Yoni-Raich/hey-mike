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
    @Test fun refusesReservedAndEmbeddedPrivateAddresses() {
        // Each of these reaches this phone or nothing public; none was refused before.
        listOf("0.1.2.3", "192.0.0.8", "198.18.0.1", "240.0.0.1", "255.255.255.255", "[64:ff9b::7f00:1]", "[64:ff9b::c0a8:101]", "[::ffff:127.0.0.1]").forEach {
            assertTrue(it, policy.check("http://$it/x") is WebUrlPolicy.Decision.Blocked)
        }
        // A NAT64 address of a public IPv4 host is public.
        assertTrue(policy.check("http://[64:ff9b::5db8:d822]/") is WebUrlPolicy.Decision.Allowed)
        assertTrue(WebUrlPolicy(resolve = { listOf(InetAddress.getByName("198.18.0.9")) }).check("https://bench.example") is WebUrlPolicy.Decision.Blocked)
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
