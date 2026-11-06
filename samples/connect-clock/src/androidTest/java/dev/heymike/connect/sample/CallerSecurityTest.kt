package dev.heymike.connect.sample

import android.content.*
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.heymike.connect.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Runs as the sample's UID, never as Mike. It must be refused by the real provider. */
@RunWith(AndroidJUnit4::class)
class CallerSecurityTest {
    @Test fun lumastandRejectsAnAppEvenWhenItUsesTheCorrectProtocol() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CompletableFuture<IMikeConnect>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IMikeConnect.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = context.bindService(Intent().setComponent(ComponentName("com.lumastand.app", "com.lumastand.app.connect.LumaConnectService")), connection, Context.BIND_AUTO_CREATE)
        assertTrue("LumaStand connection service is installed", bound)
        try {
            val service = ready.get(5,TimeUnit.SECONDS)
            try { service.bindingLevel(); fail("An unsigned agent was accepted") } catch (_: SecurityException) {}
            val reply = CompletableFuture<JsonObject>()
            val callback = object : IConnectCallback.Stub() {
                override fun onNotification(message: ConnectMessage) { message.close() }
                override fun onResponse(message: ConnectMessage) {
                    try { reply.complete(ConnectProtocol.parse(message.json)) } finally { message.close() }
                }
            }
            service.send(ConnectMessage(ConnectProtocol.request("attacker-test", "tools/call", buildJsonObject {
                put("name","set_theme"); put("arguments",buildJsonObject { put("palette","sky") })
                put("_meta",ConnectProtocol.meta(requestId="attacker-test",approved=true))
            }).toString()),callback)
            val body=reply.get(5,TimeUnit.SECONDS)
            assertEquals(-32600, body.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
            assertFalse(body.containsKey("result"))
        } finally { context.unbindService(connection) }
    }
}
