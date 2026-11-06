/* SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.automations

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.service.notification.StatusBarNotification

/** A content-free, fail-closed boundary shared by live delivery and replay. */
internal object PersonalNotificationPolicy {
    fun allows(context: Context, posted: StatusBarNotification?): Boolean = runCatching {
        val user = posted?.user ?: return false
        // Match the Android user, never the package, UID, notification key or a fixed user ID.
        // This also rejects USER_ALL and other special/unknown handles.
        if (user != Process.myUserHandle()) return false
        val users = context.getSystemService(UserManager::class.java) ?: return false
        // Android normally ignores work-profile listeners. Keep the boundary even if an
        // OEM delivers callbacks there. API 33 also identifies private/clone profiles.
        !users.isManagedProfile && (Build.VERSION.SDK_INT < 33 || !users.isProfile)
    }.getOrDefault(false)
}
