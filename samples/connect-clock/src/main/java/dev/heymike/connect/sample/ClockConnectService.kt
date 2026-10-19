package dev.heymike.connect.sample

import android.content.Intent
import dev.heymike.connect.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

class ClockConnectService : MikeConnectService() {
    private val prefs by lazy { getSharedPreferences("clock", MODE_PRIVATE) }
    private fun theme() = buildJsonObject { put("color", prefs.getString("color","white")); put("writes", prefs.getInt("writes",0)) }
    override fun MikeConnect.Builder.configure() {
        capability("get_theme") { title="Get test theme"; description="Read the test clock's color and write count."; handleJson { theme() } }
        capability("set_theme") {
            title="Set test theme"; description="Change the test clock color. Repeated stable requestIds must change the write count only once."; access=Access.WRITE
            inputSchema=buildJsonObject { put("type","object"); put("additionalProperties",false); put("properties",buildJsonObject {
                put("color",buildJsonObject { put("type","string"); put("enum",JsonArray(listOf(JsonPrimitive("white"),JsonPrimitive("blue")))) })
            }); put("required",JsonArray(listOf(JsonPrimitive("color")))) }
            handleJson { args -> prefs.edit().putString("color",args.string("color")).putInt("writes",prefs.getInt("writes",0)+1).commit(); theme() }
        }
        capability("choose_color") { title="Choose test color"; description="Ask the user which color, then return their answer."; handleJson { buildJsonObject { put("choice",requireChoice("color","Which test color?",listOf("white","blue"))) } } }
        capability("slow_read") { title="Slow read"; description="Wait ten seconds to test Stop and cancellation."; handleJson { delay(10_000); theme() } }
        capability("sensitive_test") { title="Sensitive test"; description="Simulated sensitive action. Does not delete or send anything."; access=Access.SENSITIVE; handleJson { buildJsonObject { put("approved",userApproved) } } }
        capability("open_clock") { title="Open test clock"; description="Open the SDK test clock."; access=Access.WRITE; ui=Ui.ALWAYS; handleJson { openUi(Intent(this@ClockConnectService,ClockActivity::class.java)); buildJsonObject { put("opened",true) } } }
    }
}
