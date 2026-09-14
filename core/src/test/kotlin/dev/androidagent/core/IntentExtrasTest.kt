package dev.androidagent.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentExtrasTest {

    private fun parse(json: String) = IntentExtras.parse(Json.parseToJsonElement(json))

    private fun ok(json: String): Map<String, IntentExtra> =
        (parse(json) as? IntentExtras.Parsed.Ok)?.extras ?: error("expected Ok for $json, got ${parse(json)}")

    private fun reason(json: String): String =
        (parse(json) as? IntentExtras.Parsed.Invalid)?.reason ?: error("expected Invalid for $json")

    @Test fun aTimerArrivesWithTheTypesTheClockReads() {
        // getIntExtra on a long returns the default, so a small number is an int.
        val extras = ok("""{"android.intent.extra.alarm.LENGTH":300,"android.intent.extra.alarm.SKIP_UI":true,"android.intent.extra.alarm.MESSAGE":"tea"}""")
        assertEquals(IntentExtra.Int32(300), extras["android.intent.extra.alarm.LENGTH"])
        assertEquals(IntentExtra.Flag(true), extras["android.intent.extra.alarm.SKIP_UI"])
        assertEquals(IntentExtra.Text("tea"), extras["android.intent.extra.alarm.MESSAGE"])
    }

    @Test fun aNumberTooBigForAnIntIsALongAndATypeCanBeStated() {
        assertEquals(IntentExtra.Int64(1_757_000_000_000), ok("""{"beginTime":1757000000000}""")["beginTime"])
        assertEquals(IntentExtra.Int64(5), ok("""{"x":{"type":"long","value":5}}""")["x"])
        assertEquals(IntentExtra.Real(1.5), ok("""{"x":1.5}""")["x"])
        assertEquals(IntentExtra.TextList(listOf("a@b.com")), ok("""{"android.intent.extra.EMAIL":["a@b.com"]}""")["android.intent.extra.EMAIL"])
    }

    @Test fun noExtrasIsAnEmptyMap() {
        assertTrue((IntentExtras.parse(null) as IntentExtras.Parsed.Ok).extras.isEmpty())
    }

    @Test fun somethingThatIsNotAPlainValueIsRefused() {
        assertEquals("extras_malformed", reason("""["a"]"""))
        assertEquals("extra_invalid", reason("""{"x":{"nested":1}}"""))
        assertEquals("extra_invalid", reason("""{"x":[1,2]}"""))
        assertEquals("extra_invalid", reason("""{"x":{"type":"int","value":"5"}}"""))
        assertEquals("extra_invalid", reason("""{"x":{"type":"parcelable","value":"5"}}"""))
        assertEquals("extra_key_malformed", reason("""{"bad key":1}"""))
    }

    @Test fun aLocalDataLocationCannotBeSmuggledInAsAString() {
        assertEquals("extra_scheme_blocked", reason("""{"android.intent.extra.STREAM":"content://media/external/images/1"}"""))
        assertEquals("extra_scheme_blocked", reason("""{"x":["https://ok","file:///sdcard/secret"]}"""))
    }

    @Test fun extrasAreBounded() {
        val many = (0..IntentExtras.MAX_EXTRAS).joinToString(",", "{", "}") { "\"k$it\":1" }
        assertEquals("extras_too_many", reason(many))
        assertEquals("extra_invalid", reason("""{"x":"${"a".repeat(IntentExtras.MAX_VALUE_CHARS + 1)}"}"""))
    }
}
