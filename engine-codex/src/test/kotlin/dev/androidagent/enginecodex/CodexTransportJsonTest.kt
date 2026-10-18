package dev.androidagent.enginecodex

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CodexTransportJsonTest {
    @Test fun trailingCommasDoNotChangeQuotedTextOrEscapedQuotes() {
        val value = CodexTransportJson.parse("""{"text":"hello,} world \\\" still,]","array":[1,2,],}""")
        assertEquals("hello,} world \\\" still,]", value["text"]!!.jsonPrimitive.content)
        assertEquals(2, value["array"]!!.jsonArray.size)
    }
    @Test fun incompleteAndOtherMalformedJsonStillFail() {
        for (line in listOf("{\"text\":", "{\"text\":,}", "{\"text\":1,,}", "{\"text\":\"cut,}")) {
            assertTrue(line, runCatching { CodexTransportJson.parse(line) }.isFailure)
        }
    }
}
