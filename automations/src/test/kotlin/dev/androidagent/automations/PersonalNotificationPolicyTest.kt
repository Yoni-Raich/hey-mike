/* SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.automations

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PersonalNotificationPolicyTest.Host::class, sdk = [30, 35], manifest = Config.NONE)
class PersonalNotificationPolicyTest {
    class Host : Application(), AutomationHostOwner {
        var hostLookups = 0
        override val automationHost: AutomationHost? get() { hostLookups++; return null }
    }

    private val host get() = RuntimeEnvironment.getApplication() as Host
    private val users get() = host.getSystemService(UserManager::class.java)

    @Test fun personalUserIsAcceptedWithoutReadingPayload() {
        assertTrue(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), true)))
    }

    @Test fun workPrivateOtherAndAllUsersAreRejectedEvenForTheSamePackage() {
        for (id in listOf(10, 11, 12, -1, -2, -3, -10000)) {
            assertFalse("user $id", PersonalNotificationPolicy.allows(host, posted(user(id), true)))
        }
    }

    @Test fun missingOrUnreadableIdentityIsRejectedBeforePayload() {
        assertFalse(PersonalNotificationPolicy.allows(host, null))
        assertFalse(PersonalNotificationPolicy.allows(host, posted(null, true)))
        val unreadable = object : StatusBarNotification(APP, APP, 1, null, 10000, 0, 0, Notification(), user(0), 1L) {
            private var constructed = false
            init { constructed = true }
            override fun getUser(): UserHandle = throw SecurityException("fixture identity unavailable")
            override fun getNotification(): Notification {
                check(!constructed) { "Must not read payload" }
                return super.getNotification()
            }
        }
        assertFalse(PersonalNotificationPolicy.allows(host, unreadable))
    }

    @Test fun unavailableUserServiceFailsClosed() {
        for (fail in listOf(false, true)) {
            val unavailable = object : ContextWrapper(host) {
                override fun getSystemService(name: String): Any? {
                    if (name == Context.USER_SERVICE) {
                        if (fail) throw SecurityException("fixture service unavailable")
                        return null
                    }
                    return super.getSystemService(name)
                }
            }
            assertFalse(PersonalNotificationPolicy.allows(unavailable, posted(Process.myUserHandle(), true)))
        }
    }

    @Test fun listenerInsideManagedProfileIsRejected() {
        shadowOf(users).setManagedProfile(true)
        assertFalse(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), true)))
    }

    @Test @Config(sdk = [35]) fun listenerInsidePrivateOrCloneProfileIsRejected() {
        shadowOf(users).addProfile(0, 0, "Fixture profile", org.robolectric.shadows.ShadowUserManager.FLAG_PROFILE)
        assertFalse(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), true)))
    }

    @Test fun callbackRejectsProfilesBeforePayloadAndConsumerAccess() {
        val controller = Robolectric.buildService(AutomationNotificationListener::class.java).create()
        try {
            for (id in listOf(10, 11, -1)) controller.get().onNotificationPosted(posted(user(id), true))
            assertEquals(0, host.hostLookups)
            controller.get().onNotificationPosted(posted(Process.myUserHandle()))
            assertEquals(1, host.hostLookups)
        } finally { controller.destroy() }
    }

    @Test fun secondaryPersonalUserDoesNotRequireUserZero() {
        val original = Process.myUid()
        try {
            org.robolectric.shadows.ShadowProcess.setUid(2_010_000)
            assertTrue(PersonalNotificationPolicy.allows(host, posted(Process.myUserHandle(), true)))
            assertFalse(PersonalNotificationPolicy.allows(host, posted(user(0), true)))
        } finally { org.robolectric.shadows.ShadowProcess.setUid(original) }
    }

    private fun posted(origin: UserHandle?, protectPayload: Boolean = false): StatusBarNotification =
        object : StatusBarNotification(APP, APP, 1, null, 10000, 0, 0, Notification(), origin ?: user(0), 1L) {
            private var armed = false
            init { armed = protectPayload }
            override fun getUser(): UserHandle? = origin
            override fun getNotification(): Notification {
                check(!armed) { "Rejected payload was accessed" }
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
