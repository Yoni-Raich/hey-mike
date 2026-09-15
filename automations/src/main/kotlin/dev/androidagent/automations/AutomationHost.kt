package dev.androidagent.automations

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import dev.androidagent.core.AutomationActions
import dev.androidagent.core.AutomationContext
import dev.androidagent.core.AutomationEvaluator
import dev.androidagent.core.AutomationEvent
import dev.androidagent.core.AutomationHistory
import dev.androidagent.core.AutomationLibrary
import dev.androidagent.core.AutomationRunReport
import dev.androidagent.core.AutomationRunner
import dev.androidagent.core.AutomationTriggerKind
import dev.androidagent.core.AutomationWakeups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Lets a system-created component — an alarm receiver, the notification
 * listener — reach the one host without a static field of its own.
 *
 * `AgentApplication` implements this. The same shape as `A11yServiceHandle`
 * and for the same reason: the system constructs these classes, so they have
 * nowhere to be injected into.
 */
interface AutomationHostOwner {
    val automationHost: AutomationHost?
}

internal fun Context.automationHost(): AutomationHost? =
    (applicationContext as? AutomationHostOwner)?.automationHost

/**
 * Where a trigger becomes a run.
 *
 * The host owns nothing clever: `:core` decides which rules an event fires and
 * what to do about it, and this class is the part that cannot be unit-tested
 * because it is alarms, broadcasts and a notification listener. Keeping it
 * thin is deliberate — everything worth getting right lives on the other side
 * of [AutomationEvaluator] and [AutomationRunner].
 *
 * One alarm serves every scheduled rule ([AutomationWakeups.nextRunAt]) and is
 * re-armed after every firing and after any change to the rules. The alarm is
 * a wake-up, not a decision: what actually fires is re-checked against each
 * rule's own schedule, so a coalesced or early alarm fires nothing.
 *
 * Runs are serialised behind [runLock]. Two rules that both want the screen
 * cannot have it at once, and the coordinator would refuse the second anyway;
 * queueing them here means the second waits its turn instead of being dropped
 * for a collision it did not cause.
 */
class AutomationHost(
    private val context: Context,
    val library: AutomationLibrary,
    private val history: AutomationHistory,
    private val actions: AutomationActions,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** True when a turn could run now: signed in, runtime up, nothing else running. */
    private val agentAvailable: () -> Boolean = { false },
    /** Told about every run, for a log line or a settings screen. */
    private val onReport: (AutomationRunReport) -> Unit = {},
) {

    private val evaluator = AutomationEvaluator(history)
    private val runner = AutomationRunner(actions, history, { ZonedDateTime.now(zone()) })
    private val alarms = AutomationAlarms(context)
    private val runLock = Mutex()

    @Volatile private var started = false

    /** Charging and screen, refreshed by [deviceStateReceiver] and read on every event. */
    @Volatile private var lastDeviceState: Map<String, String> = emptyMap()

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(deviceStateReceiver, deviceStateFilter)
        lastDeviceState = readDeviceState()
        rearm()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(deviceStateReceiver) }
        alarms.cancel()
    }

    /**
     * Which triggers this phone can actually serve right now.
     *
     * Handed to `AutomationToolGateway`, which saves a rule it cannot serve and
     * reports it **dormant** rather than pretending it is live. `place` is
     * absent because no geofence source is built yet — see the architecture
     * notes on why that needs a dependency decision first.
     */
    fun supportedTriggers(): Set<AutomationTriggerKind> = buildSet {
        add(AutomationTriggerKind.SCHEDULE)
        add(AutomationTriggerKind.DEVICE_STATE)
        add(AutomationTriggerKind.MANUAL)
        if (AutomationNotificationListener.isEnabled(context)) add(AutomationTriggerKind.NOTIFICATION)
    }

    /** True when the clock can be trusted to the minute rather than to the hour. */
    fun canFireOnTime(): Boolean = alarms.canScheduleExact()

    /**
     * Packages the notification listener may look at, read before it touches a
     * title or a body. Empty means it has nothing to do.
     */
    fun watchedNotificationPackages(): Set<String> = AutomationWakeups.watchedPackages(library.all())

    /** Deliver an event. Evaluation and any run happen off the caller's thread. */
    fun onEvent(event: AutomationEvent) {
        scope.launch {
            val context = snapshot(event.at)
            val outcomes = evaluator.evaluate(library.all(), event, context)
            for (outcome in outcomes.filterIsInstance<AutomationEvaluator.Outcome.Fired>()) {
                val report = runLock.withLock { runner.run(outcome) }
                onReport(report)
                if (!report.ok) {
                    Log.w(TAG, "Rule ${report.ruleId} stopped: ${report.errorType} ${report.message.orEmpty()}")
                }
            }
            // A rule that just fired has a new cooldown and a new next run.
            rearm()
        }
    }

    /** Run one rule by name, as if a person had asked for it. */
    fun runNow(ruleId: String) = onEvent(AutomationEvent.Manual(ruleId, ZonedDateTime.now(zone())))

    /**
     * Point the single alarm at the earliest rule that is next due.
     *
     * Called after every firing, after a rule is saved, enabled or disabled,
     * and at boot — the alarm itself does not survive a restart.
     */
    fun rearm() {
        val next = AutomationWakeups.nextRunAt(library.all(), ZonedDateTime.now(zone()))
        if (next == null) alarms.cancel() else alarms.armFor(next)
    }

    private fun snapshot(now: ZonedDateTime) = AutomationContext(
        now = now,
        // No geofence source yet, so nothing is ever inside a place and an
        // `at_place` condition is false rather than quietly true.
        places = emptySet(),
        deviceState = lastDeviceState,
        userReachable = userReachable(),
        agentAvailable = agentAvailable(),
    )

    /**
     * True when a person could answer a question right now.
     *
     * Screen on and not behind the lock screen. Deliberately strict: this gates
     * every action that needs the user, and a host that answers `true` from a
     * pocket produces a phone that starts talking in a meeting.
     */
    private fun userReachable(): Boolean = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        power?.isInteractive == true && keyguard?.isKeyguardLocked != true
    }.getOrDefault(false)

    private fun readDeviceState(): Map<String, String> = runCatching {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        buildMap {
            battery?.let { put("power", if (it.isCharging) "charging" else "discharging") }
            power?.let { put("screen", if (it.isInteractive) "on" else "off") }
        }
    }.getOrDefault(emptyMap())

    private val deviceStateFilter = IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        // Screen actions cannot be declared in a manifest; they are only ever
        // delivered to a receiver registered at runtime, which is why the host
        // registers this one itself rather than shipping another <receiver>.
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
    }

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(received: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            lastDeviceState = readDeviceState()
            val now = ZonedDateTime.now(zone())
            val change = when (action) {
                Intent.ACTION_POWER_CONNECTED -> AutomationEvent.DeviceState("power", "charging", now)
                Intent.ACTION_POWER_DISCONNECTED -> AutomationEvent.DeviceState("power", "discharging", now)
                Intent.ACTION_SCREEN_ON -> AutomationEvent.DeviceState("screen", "on", now)
                Intent.ACTION_SCREEN_OFF -> AutomationEvent.DeviceState("screen", "off", now)
                Intent.ACTION_USER_PRESENT -> AutomationEvent.DeviceState("screen", "unlocked", now)
                else -> return
            }
            onEvent(change)
        }
    }

    companion object {
        const val TAG = "Automations"
    }
}
