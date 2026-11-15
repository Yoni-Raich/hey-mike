/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.devicetools

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Real [SystemPlatform] over the app's own system services. */
class AndroidSystemPlatform(context: Context) : SystemPlatform {
    private val context: Context = context.applicationContext ?: context
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val camera get() = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }

    @Volatile private var torchOn: Boolean? = null

    init {
        runCatching {
            camera?.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId()) torchOn = enabled
                }
            }, main)
        }
    }

    private fun canWriteSettings() = Settings.System.canWrite(context)
    private fun hasPolicyAccess() = notifications?.isNotificationPolicyAccessGranted == true
    private fun hasSecureWrite() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    override fun status(): Map<String, Any?> {
        val a = audio
        val resolver = context.contentResolver
        return mapOf(
            "volumes" to STREAMS.mapValues { (_, type) ->
                mapOf("level" to a?.getStreamVolume(type), "max" to a?.getStreamMaxVolume(type))
            },
            "ringer" to when (a?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                else -> null
            },
            "dnd" to when (notifications?.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "total"
                else -> null
            },
            "brightness" to runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull(),
            "brightnessAuto" to runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }.getOrNull(),
            "screenTimeoutSeconds" to runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT) / 1000 }.getOrNull(),
            "autoRotate" to runCatching { Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrNull(),
            "torch" to torchOn,
            "grants" to mapOf(
                "write_settings" to canWriteSettings(),
                "notification_policy" to hasPolicyAccess(),
                "usage_access" to hasUsageAccess(),
                "write_secure_settings" to hasSecureWrite(),
                "location" to (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            ),
        )
    }

    private fun torchCameraId(): String? = runCatching {
        val cm = camera ?: return null
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    override fun setTorch(on: Boolean): SystemWrite {
        val id = torchCameraId() ?: return SystemWrite.Failed("no_flash", "This phone has no flash unit.")
        return runCatching { camera!!.setTorchMode(id, on) }.fold(
            { torchOn = on; SystemWrite.Done },
            { SystemWrite.Failed("torch_failed", "The flash is busy (maybe the camera is open): ${it.message}") },
        )
    }

    override fun setVolume(stream: String, level: Int?, percent: Int?, showUi: Boolean): Pair<SystemWrite, Map<String, Int>> {
        val a = audio ?: return SystemWrite.Failed("no_audio", "No audio service.") to emptyMap()
        val type = STREAMS.getValue(stream)
        val max = a.getStreamMaxVolume(type)
        val min = a.getStreamMinVolume(type)
        val wanted = (level ?: percent?.let { (it * max + 50) / 100 })
        val result = if (wanted == null) {
            SystemWrite.Done
        } else {
            val target = wanted.coerceIn(min, max)
            // Turning ring/notification to 0 is a ringer-mode change, which
            // Android guards behind Do Not Disturb access.
            try {
                a.setStreamVolume(type, target, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
                SystemWrite.Done
            } catch (_: SecurityException) {
                SystemWrite.Missing("notification_policy", "Silencing this stream needs Do Not Disturb access for Hey Mike.")
            }
        }
        return result to mapOf("level" to a.getStreamVolume(type), "max" to max)
    }

    override fun setRinger(mode: String): SystemWrite {
        val a = audio ?: return SystemWrite.Failed("no_audio", "No audio service.")
        val value = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        if (value == AudioManager.RINGER_MODE_SILENT && !hasPolicyAccess()) {
            return SystemWrite.Missing("notification_policy", "Silent mode needs Do Not Disturb access for Hey Mike.")
        }
        return try {
            a.ringerMode = value
            SystemWrite.Done
        } catch (_: SecurityException) {
            SystemWrite.Missing("notification_policy", "Changing the ringer needs Do Not Disturb access for Hey Mike.")
        }
    }

    override fun setDnd(mode: String): SystemWrite {
        val nm = notifications ?: return SystemWrite.Failed("no_service", "No notification service.")
        if (!hasPolicyAccess()) return SystemWrite.Missing("notification_policy", "Do Not Disturb needs Do Not Disturb access for Hey Mike.")
        nm.setInterruptionFilter(
            when (mode) {
                "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                "total" -> NotificationManager.INTERRUPTION_FILTER_NONE
                else -> NotificationManager.INTERRUPTION_FILTER_ALL
            },
        )
        return SystemWrite.Done
    }

    private inline fun systemWrite(block: () -> Unit): SystemWrite {
        if (!canWriteSettings()) return SystemWrite.Missing("write_settings", "Changing system settings needs \"Modify system settings\" for Hey Mike.")
        return runCatching(block).fold({ SystemWrite.Done }, { SystemWrite.Failed("write_failed", it.message ?: "The setting was refused.") })
    }

    override fun setBrightness(level: Int?, auto: Boolean?): SystemWrite = systemWrite {
        val resolver = context.contentResolver
        if (auto != null) {
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
        if (level != null) {
            // A manual level only shows while automatic brightness is off.
            if (auto == null) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(1, 255))
        }
    }

    override fun setScreenTimeout(seconds: Int): SystemWrite = systemWrite {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
    }

    override fun setAutoRotate(on: Boolean): SystemWrite = systemWrite {
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: T? = null
        main.post { out = runCatching(block).getOrNull(); latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    override fun clipboardGet(): String? = onMain {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip ?: return@onMain null
        if (clip.itemCount == 0) return@onMain null
        clip.getItemAt(0).coerceToText(context)?.toString()
    }

    override fun clipboardSet(text: String): SystemWrite {
        val ok = onMain {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Hey Mike", text))
            true
        }
        return if (ok == true) SystemWrite.Done else SystemWrite.Failed("clipboard_failed", "Android refused the clipboard write.")
    }

    override suspend fun location(precise: Boolean, timeoutMs: Long): LocationFix? {
        val permission = if (precise) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$permission not granted")
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = lm.getProviders(true)
        val order = if (precise) listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val provider = order.firstOrNull { it in providers } ?: providers.firstOrNull() ?: return null
        val fresh: Location? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            }
        }
        val fix = fresh ?: withContext(Dispatchers.IO) {
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        } ?: return null
        return LocationFix(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyM = fix.accuracy.takeIf { fix.hasAccuracy() },
            altitudeM = fix.altitude.takeIf { fix.hasAltitude() },
            speedMps = fix.speed.takeIf { fix.hasSpeed() },
            provider = fix.provider,
            timeMs = fix.time,
            fresh = fresh != null,
        )
    }

    override fun hasUsageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    override fun usage(sinceMs: Long, untilMs: Long, limit: Int): List<UsageRow> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        return usm.queryAndAggregateUsageStats(sinceMs, untilMs).values
            .filter { it.totalTimeInForeground > 0 && it.packageName != context.packageName }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)
            .map { UsageRow(it.packageName, it.totalTimeInForeground, it.lastTimeUsed) }
    }

    override fun getSetting(namespace: String, key: String): String? = runCatching {
        val r = context.contentResolver
        when (namespace) {
            "system" -> Settings.System.getString(r, key)
            "secure" -> Settings.Secure.getString(r, key)
            else -> Settings.Global.getString(r, key)
        }
    }.getOrNull()

    override fun putSetting(namespace: String, key: String, value: String?): SystemWrite {
        val r = context.contentResolver
        return when (namespace) {
            "system" -> systemWrite { Settings.System.putString(r, key, value) }
            else -> {
                if (!hasSecureWrite()) {
                    return SystemWrite.Failed(
                        "secure_settings_missing",
                        "Secure and global settings need WRITE_SECURE_SETTINGS, which Hey Mike grants itself once over Wireless ADB.",
                    )
                }
                runCatching {
                    if (namespace == "secure") Settings.Secure.putString(r, key, value) else Settings.Global.putString(r, key, value)
                }.fold(
                    { if (it) SystemWrite.Done else SystemWrite.Failed("write_failed", "Android refused that setting.") },
                    { SystemWrite.Failed("write_failed", it.message ?: "Android refused that setting.") },
                )
            }
        }
    }

    private companion object {
        val STREAMS = mapOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "call" to AudioManager.STREAM_VOICE_CALL,
            "system" to AudioManager.STREAM_SYSTEM,
        )
    }
}
