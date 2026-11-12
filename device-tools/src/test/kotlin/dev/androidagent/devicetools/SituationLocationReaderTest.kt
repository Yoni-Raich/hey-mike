package dev.androidagent.devicetools

import dev.androidagent.devicetools.SituationLocationAdapter.Fix
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** One interactive read against a fake LocationManager on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SituationLocationReaderTest {
    private val wallBase = 1_794_492_000_000L
    private val elapsedBase = 50_000_000L

    private inner class Phone(private val scope: TestScope) : SituationLocationReader.Backend {
        override var sdk = 34
        var level: String? = "coarse"
        var enabled = true
        var providers = listOf("passive", "network", "fused", "gps")
        val cache = mutableMapOf<String, Fix>()
        /** What each provider does: a fix, null (the provider has none), "hang", or an exception to throw. */
        val behavior = mutableMapOf<String, Any?>()
        val requested = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val pending = mutableMapOf<String, (Fix?) -> Unit>()
        val cacheReads = mutableListOf<String>()
        var revokeWhileWaiting = false

        override fun permission() = level
        override fun profile(): String? = "current_android_user:0"
        override fun bootCount(): Int? = 104
        override fun locationEnabled() = enabled
        override fun enabledProviders() = providers
        override fun lastKnown(provider: String): Fix? { cacheReads += provider; return cache[provider] }
        override fun current(provider: String, onResult: (Fix?) -> Unit): () -> Unit {
            requested += provider
            when (val action = behavior[provider]) {
                is Exception -> throw action
                "hang" -> pending[provider] = onResult
                else -> { if (revokeWhileWaiting) level = null; onResult(action as Fix?) }
            }
            return { cancelled += provider; pending.remove(provider) }
        }
        override fun now() = wallBase + scope.currentTime
        override fun elapsed() = elapsedBase + scope.currentTime
        fun fix(ageMs: Long, provider: String = "network", accuracy: Float = 1500f) =
            Fix(32.08, 34.78, accuracy, elapsed() - ageMs, provider)
    }

    private fun TestScope.phone() = Phone(this)

    @Test fun coarseOnlyTriesFusedThenNetworkAndNeverGpsOrPassive() = runTest {
        val phone = phone().apply { behavior["fused"] = null }
        phone.behavior["network"] = phone.fix(1_000)
        val result = SituationLocationReader(phone).read()
        assertEquals("fresh", result.status)
        assertEquals("network", result.provider)
        assertEquals("live", result.source)
        assertEquals("coarse", result.permission)
        assertEquals(listOf("fused", "network"), phone.requested)
        assertEquals(listOf("cache:none", "fused:no fix", "network:fix"), result.tried)
        assertFalse("passive" in phone.cacheReads)
        assertTrue(result.fresh(phone.now()))
    }

    @Test fun fineAddsGpsAfterTheOthersAndReadsThePassiveCache() = runTest {
        val phone = phone().apply { level = "fine"; behavior["fused"] = null; behavior["network"] = null }
        phone.behavior["gps"] = phone.fix(500, "gps", 8f)
        val result = SituationLocationReader(phone).read()
        assertEquals(listOf("fused", "network", "gps"), phone.requested)
        assertEquals("gps", result.provider)
        assertTrue("passive" in phone.cacheReads)
    }

    @Test fun androidElevenHasNoFusedProviderInLocationManager() = runTest {
        val phone = phone().apply { sdk = 30; behavior["network"] = null }
        assertEquals("no fix", SituationLocationReader(phone).read().status)
        assertEquals(listOf("network"), phone.requested)
    }

    @Test fun silentProvidersTimeOutWithinOneBudgetAndEveryRequestIsCancelled() = runTest {
        val phone = phone().apply { behavior["fused"] = "hang"; behavior["network"] = "hang" }
        val started = currentTime
        val result = SituationLocationReader(phone).read()
        assertEquals("timeout", result.status)
        assertEquals(SituationLocationReader.BUDGET_MS, currentTime - started)
        assertEquals(listOf("fused", "network"), phone.cancelled)
        assertTrue(phone.pending.isEmpty())
        assertNull(result.latitude)
        assertFalse(result.fresh(phone.now()))
        assertEquals(listOf("cache:none", "fused:timeout", "network:timeout"), result.tried)
    }

    @Test fun aProviderThatFailsFastLeavesItsTimeToTheNext() = runTest {
        val phone = phone().apply { behavior["fused"] = null; behavior["network"] = "hang" }
        SituationLocationReader(phone).read()
        assertEquals(SituationLocationReader.BUDGET_MS, currentTime)
    }

    @Test fun stoppingTheCallerCancelsTheRequestAtOnce() = runTest {
        val phone = phone().apply { behavior["fused"] = "hang" }
        val read = async(start = CoroutineStart.UNDISPATCHED) { SituationLocationReader(phone).read() }
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf("fused"), phone.requested)
        read.cancel(); runCurrent()
        assertEquals(listOf("fused"), phone.cancelled)
        assertTrue(phone.pending.isEmpty())
    }

    @Test fun aLateCallbackAfterTimeoutIsIgnored() = runTest {
        val phone = phone().apply { providers = listOf("network"); behavior["network"] = "hang" }
        var late: ((Fix?) -> Unit)? = null
        val wrapped = object : SituationLocationReader.Backend by phone {
            override fun current(provider: String, onResult: (Fix?) -> Unit): () -> Unit { late = onResult; return phone.current(provider, onResult) }
        }
        assertEquals("timeout", SituationLocationReader(wrapped).read().status)
        late!!.invoke(phone.fix(0)) // must not crash or change anything
    }

    @Test fun aFreshCacheKeepsItsOwnTimeAndNeedsNoRequest() = runTest {
        val phone = phone()
        phone.cache["network"] = phone.fix(60_000)
        val result = SituationLocationReader(phone).read()
        assertEquals("fresh", result.status)
        assertEquals("cache", result.source)
        assertEquals(phone.now() - 60_000, result.observedAt)
        assertEquals(result.observedAt + 120_000, result.expiresAt)
        assertTrue(phone.requested.isEmpty())
    }

    @Test fun anOlderCacheIsOnlyTheLastKnownFixWithItsOriginalTime() = runTest {
        val phone = phone().apply { behavior["fused"] = "hang"; behavior["network"] = "hang" }
        phone.cache["fused"] = phone.fix(7 * 60_000, "fused")
        val readAt = phone.now()
        val result = SituationLocationReader(phone).read()
        assertEquals("timeout", result.status)
        assertNull(result.latitude)
        val last = result.lastKnown!!
        assertEquals(readAt - 7 * 60_000, last.fixAt)
        assertEquals(104, last.bootCount)
        assertEquals("cache:7m old", result.tried.first())
        assertNull(result.asFix())
    }

    @Test fun aCacheOlderThanHalfAnHourIsNotKept() = runTest {
        val phone = phone().apply { behavior["fused"] = null; behavior["network"] = null }
        phone.cache["network"] = phone.fix(31 * 60_000)
        val result = SituationLocationReader(phone).read()
        assertEquals("no fix", result.status)
        assertNull(result.lastKnown)
    }

    @Test fun permissionSwitchAndProviderFailuresAreToldApart() = runTest {
        val none = phone().apply { level = null }
        assertEquals("permission denied", SituationLocationReader(none).read().status)
        assertTrue(none.requested.isEmpty())

        val off = phone().apply { enabled = false }
        assertEquals("location disabled", SituationLocationReader(off).read().status)
        assertTrue(off.cacheReads.isEmpty())

        val noProvider = phone().apply { providers = listOf("passive", "gps") } // gps needs fine
        assertEquals("provider unavailable", SituationLocationReader(noProvider).read().status)

        val missing = phone().apply { behavior["fused"] = IllegalArgumentException("unknown provider"); behavior["network"] = IllegalArgumentException("x") }
        assertEquals("provider unavailable", SituationLocationReader(missing).read().status)

        val broken = phone().apply { behavior["fused"] = IllegalStateException("binder"); behavior["network"] = null }
        val result = SituationLocationReader(broken).read()
        assertEquals("no fix", result.status)
        assertEquals(listOf("cache:none", "fused:error", "network:no fix"), result.tried)

        val revoked = phone().apply { behavior["fused"] = SecurityException("revoked") }
        assertEquals("permission denied", SituationLocationReader(revoked).read().status)
    }

    @Test fun aFixThatArrivesAfterThePermissionWasRevokedIsDropped() = runTest {
        val phone = phone().apply { revokeWhileWaiting = true }
        phone.behavior["fused"] = phone.fix(0, "fused")
        val result = SituationLocationReader(phone).read()
        assertEquals("permission denied", result.status)
        assertNull(result.latitude)
    }
}
