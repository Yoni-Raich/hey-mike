package dev.heymike.connect

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class RequestLedgerTest {
    @Test fun processRestartNeverRepeatsAnUnfinishedWrite() {
        val dir = Files.createTempDirectory("connect-ledger").toFile()
        val ledger = RequestLedger(dir)
        assertNull(ledger.reserve("agent", "id", "args"))
        val afterCrash = RequestLedger(dir).reserve("agent", "id", "args")!!
        assertEquals("outcome_unknown", afterCrash["_meta"]!!.jsonObject.string("dev.heymike/code"))
        assertEquals("request_id_conflict", ledger.reserve("agent", "id", "other")!!["_meta"]!!.jsonObject.string("dev.heymike/code"))
    }
    @Test fun savedResultSurvivesRestartAndCallerIdsAreIsolated() {
        val dir = Files.createTempDirectory("connect-ledger").toFile()
        val ledger = RequestLedger(dir)
        ledger.reserve("a", "id", "args")
        val result = ConnectProtocol.complete(buildJsonObject { put("changed", true) })
        ledger.finish("a", "id", "args", result)
        assertEquals(result, RequestLedger(dir).reserve("a", "id", "args"))
        assertNull(ledger.reserve("b", "id", "args"))
    }
}
