// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.BadParcelableException
import android.os.Parcel
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Authentication is stubbed here. A separate physical-device test checks the real UID boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ServiceDispatchTest.TrustPackageManager::class])
class ServiceDispatchTest {
    @Test fun parcelLimitsRejectUntrustedSizesBeforeAllocatingParts() {
        val parcel=Parcel.obtain()
        try {
            ConnectMessage("{}").writeToParcel(parcel,0); parcel.setDataPosition(0)
            assertEquals("{}",ConnectMessage.CREATOR.createFromParcel(parcel).json)
            parcel.setDataSize(0); parcel.setDataPosition(0)
            parcel.writeString("{}"); parcel.writeInt(Int.MAX_VALUE); parcel.setDataPosition(0)
            try { ConnectMessage.CREATOR.createFromParcel(parcel); fail("Unbounded part count accepted") }
            catch (_: BadParcelableException) {}
            parcel.setDataSize(0); parcel.setDataPosition(0)
            parcel.writeString("x".repeat(ConnectProtocol.MAX_JSON_BYTES+1)); parcel.writeInt(0); parcel.setDataPosition(0)
            try { ConnectMessage.CREATOR.createFromParcel(parcel); fail("Oversized JSON accepted") }
            catch (_: BadParcelableException) {}
        } finally { parcel.recycle() }
    }
    @Implements(className = "android.app.ApplicationPackageManager")
    class TrustPackageManager : ShadowApplicationPackageManager() {
        @Implementation(minSdk = 28)
        protected fun hasSigningCertificate(pkg: String, bytes: ByteArray, type: Int): Boolean =
            pkg == TrustedAgents.HEY_MIKE.packageName && bytes.size == 32 && type == PackageManager.CERT_INPUT_SHA256
    }

    class Provider : MikeConnectService() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val writes = AtomicInteger()
        override fun MikeConnect.Builder.configure() {
            capability("slow_read") {
                description = "Waits so cancellation can be checked."
                handleJson {
                    entered.countDown()
                    try { awaitCancellation() } finally { cancelled.countDown() }
                }
            }
            capability("change_test") {
                description = "Counts changes for duplicate request checks."
                access = Access.WRITE
                inputSchema = buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", buildJsonObject { put("color", buildJsonObject { put("type", "string") }) })
                    put("required", JsonArray(listOf(JsonPrimitive("color"))))
                }
                handleJson { args -> buildJsonObject { put("writes", writes.incrementAndGet()); put("color", args.string("color")) } }
            }
        }
    }

    private class Callback : IConnectCallback.Stub() {
        val done = CountDownLatch(1)
        var response: JsonObject? = null
        override fun onNotification(message: ConnectMessage) { message.close() }
        override fun onResponse(message: ConnectMessage) {
            response = ConnectProtocol.parse(message.json); message.close(); done.countDown()
        }
    }

    @Test fun binderCancelStopsTheSameStringIdThatWasDispatched() {
        val lifecycle = Robolectric.buildService(Provider::class.java).create()
        val service = lifecycle.get()
        shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), TrustedAgents.HEY_MIKE.packageName)
        val remote = IMikeConnect.Stub.asInterface(service.onBind(Intent(ConnectProtocol.ACTION)))
        val callback = Callback()
        try {
            val request = ConnectProtocol.request("rpc-cancel-1", "tools/call", buildJsonObject {
                put("name", "slow_read"); put("arguments", buildJsonObject {})
                put("_meta", ConnectProtocol.meta(requestId = "stable-cancel-1"))
            })
            remote.send(ConnectMessage(request.toString()), callback)
            assertTrue("Handler did not start", service.entered.await(5, TimeUnit.SECONDS))
            remote.cancel("rpc-cancel-1")
            assertTrue("Provider handler ignored cancellation", service.cancelled.await(2, TimeUnit.SECONDS))
            assertTrue(callback.done.await(2, TimeUnit.SECONDS))
            val result = requireNotNull(callback.response).getValue("result").jsonObject
            assertEquals("cancelled", result.getValue("_meta").jsonObject.string(ConnectProtocol.PREFIX + "code"))
        } finally { lifecycle.destroy() }
    }

    @Test fun anUnknownCallerCannotListOrCallAndCannotCancel() {
        val lifecycle = Robolectric.buildService(Provider::class.java).create()
        val service = lifecycle.get()
        shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), "other.app")
        val remote = IMikeConnect.Stub.asInterface(service.onBind(Intent(ConnectProtocol.ACTION)))
        val callback = Callback()
        try {
            remote.send(ConnectMessage(ConnectProtocol.request("unknown", "tools/list").toString()), callback)
            assertTrue(callback.done.await(2, TimeUnit.SECONDS))
            assertEquals(-32600, callback.response!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
            try { remote.cancel("rpc-cancel-1"); fail("Unknown caller cancelled a request") }
            catch (_: SecurityException) {}
            assertEquals(1L, service.entered.count)
        } finally { lifecycle.destroy() }
    }

    @Test fun duplicateWritesUseTheSavedResultAndConflictingArgumentsNeverRun() {
        val lifecycle = Robolectric.buildService(Provider::class.java).create()
        val service = lifecycle.get()
        shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), TrustedAgents.HEY_MIKE.packageName)
        val remote = IMikeConnect.Stub.asInterface(service.onBind(Intent(ConnectProtocol.ACTION)))
        fun call(rpcId: String, color: String): JsonObject {
            val callback = Callback()
            remote.send(ConnectMessage(ConnectProtocol.request(rpcId,"tools/call",buildJsonObject {
                put("name","change_test"); put("arguments",buildJsonObject { put("color",color) })
                put("_meta",ConnectProtocol.meta(requestId="write-once-test"))
            }).toString()),callback)
            assertTrue(callback.done.await(5,TimeUnit.SECONDS))
            return requireNotNull(callback.response).getValue("result").jsonObject
        }
        try {
            val first=call("rpc-write-1","blue")
            assertEquals(first,call("rpc-write-2","blue"))
            val conflict=call("rpc-write-3","white")
            assertEquals("request_id_conflict",conflict.getValue("_meta").jsonObject.string(ConnectProtocol.PREFIX+"code"))
            assertEquals(1,service.writes.get())
        } finally { lifecycle.destroy() }
    }
}
