/* SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.automations

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.service.notification.StatusBarNotification
import dev.androidagent.core.CapturedMessage
import dev.androidagent.core.TaskSignalSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = PersonalNotificationPolicyTest.Host::class, sdk = [30, 35], manifest = Config.NONE)
class PersonalNotificationPolicyTest {
    class Host : Application(), AutomationHostOwner {
        var hostLookups = 0
        override val automationHost: AutomationHost? get() { hostLookups++; return null }
        val captured = mutableListOf<CapturedMessage>()
        var wantsCalls = 0
        override val taskSignals = object : TaskSignalSink {
            override fun wants(app: String): Boolean { wantsCalls++; return true }
            override fun onMessage(message: CapturedMessage) { captured += message }
        }
    }

    private val host get() = RuntimeEnvironment.getApplication() as Host
    private val users get() = host.getSystemService(UserManager::class.java)

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun personalUserIsAcceptedWithoutReadingPayload() {
        assertTrue(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), forbidPayload = true)))
    }

    @Test fun workPrivateOtherAndAllUsersAreRejectedEvenForTheSamePackage() {
        for (id in listOf(10, 11, 12, -1, -2, -3, -10000)) {
            assertFalse("user $id", PersonalNotificationPolicy.allows(host, posted(user(id), forbidPayload = true)))
        }
    }

    @Test fun missingOrUnreadableUserIsRejectedBeforePayload() {
        assertFalse(PersonalNotificationPolicy.allows(host, null))
        assertFalse(PersonalNotificationPolicy.allows(host, posted(null, forbidPayload = true)))
        val unreadable = object : StatusBarNotification(APP, APP, 1, null, 10000, 0, 0, Notification(), user(0), 1L) {
            private var constructed = false
            init { constructed = true }
            override fun getUser(): UserHandle = throw SecurityException("synthetic unavailable metadata")
            override fun getNotification(): Notification {
                check(!constructed) { "Must not read notification" }
                return super.getNotification()
            }
        }
        assertFalse(PersonalNotificationPolicy.allows(host, unreadable))
    }

    @Test fun missingOrFailedUserServiceFailsClosed() {
        for (fail in listOf(false, true)) {
            val unavailable = object : ContextWrapper(host) {
                override fun getSystemService(name: String): Any? {
                    if (name == Context.USER_SERVICE) {
                        if (fail) throw SecurityException("synthetic unavailable user service")
                        return null
                    }
                    return super.getSystemService(name)
                }
            }
            assertFalse(PersonalNotificationPolicy.allows(unavailable, posted(Process.myUserHandle(), forbidPayload = true)))
        }
    }

    @Test fun listenerRunningInsideManagedProfileIsRejected() {
        shadowOf(users).setManagedProfile(true)
        assertFalse(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), forbidPayload = true)))
    }

    @Test @Config(sdk = [35]) fun listenerRunningInsideAnotherProfileIsRejected() {
        shadowOf(users).addProfile(0, 0, "Synthetic profile", org.robolectric.shadows.ShadowUserManager.FLAG_PROFILE)
        assertFalse(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), forbidPayload = true)))
    }

    @Test fun liveCallbackDropsOtherProfilesBeforeConsultingAnyConsumer() {
        val controller = Robolectric.buildService(AutomationNotificationListener::class.java).create()
        try {
            for (id in listOf(10, 11, -1)) controller.get().onNotificationPosted(posted(user(id), forbidPayload = true))
            assertEquals(0, host.hostLookups)
            assertEquals(0, host.wantsCalls)
            assertTrue(host.captured.isEmpty())
            controller.get().onNotificationPosted(posted(Process.myUserHandle()))
            assertEquals(1, host.captured.size)
            assertEquals(APP, host.captured.single().app)
        } finally { controller.destroy() }
    }

    @Test fun activeReplaySkipsOtherProfilesAndStillCapturesPersonalNotification() {
        val controller = Robolectric.buildService(AutomationNotificationListener::class.java).create()
        try {
            val service = controller.get()
            val shadow = shadowOf(service)
            for (id in listOf(10, 11, -1)) shadow.addActiveNotification(posted(user(id), forbidPayload = true))
            shadow.addActiveNotification(posted(Process.myUserHandle()))
            service.onListenerConnected()
            assertEquals(1, host.wantsCalls)
            assertEquals(1, host.captured.size)
            assertEquals(0, host.hostLookups) // Reconnect must never replay automation actions.
        } finally { controller.destroy() }
    }

    @Test fun directReaderCannotBypassProfileBoundary() {
        assertTrue(TaskNotificationReader.messages(host, posted(user(11), forbidPayload = true)).isEmpty())
        assertEquals(1, TaskNotificationReader.messages(host, posted(Process.myUserHandle())).size)
    }

    @Test fun secondaryPersonalUserDoesNotRequireUserZero() {
        val original = Process.myUid()
        try {
            org.robolectric.shadows.ShadowProcess.setUid(2_010_000)
            assertTrue(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), forbidPayload = true)))
            assertFalse(PersonalNotificationPolicy.allows(host, posted(user(0), forbidPayload = true)))
        } finally { org.robolectric.shadows.ShadowProcess.setUid(original) }
    }

    private fun posted(origin: UserHandle?, forbidPayload: Boolean = false): StatusBarNotification =
        object : StatusBarNotification(APP, APP, 1, null, 10000, 0, 0, Notification().apply {
            extras = Bundle().apply {
                putCharSequence(Notification.EXTRA_TITLE, "Synthetic sender")
                putCharSequence(Notification.EXTRA_TEXT, "Synthetic message")
            }
        }, origin ?: user(0), 1L) {
            // Android's constructor calls this virtual getter too. Arm the sentinel
            // only once the synthetic object exists, before handing it to Mike.
            private var payloadProtected = false
            init { payloadProtected = forbidPayload }
            override fun getUser(): UserHandle? = origin
            override fun getNotification(): Notification {
                check(!payloadProtected) { "Rejected notification payload was accessed" }
                return super.getNotification()
            }
        }

    private fun user(id: Int): UserHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(id)
            parcel.setDataPosition(0)
            UserHandle.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private companion object { const val APP = "com.example.samepackage" }
}
