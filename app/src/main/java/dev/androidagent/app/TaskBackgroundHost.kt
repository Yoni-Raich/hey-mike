/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Durable wakeups complement the live collectors. Android may delay them in Doze. */
internal class TaskBackgroundHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val desk: TaskDesk,
) {
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!desk.state.value.settings.discoveryOn || !desk.state.value.settings.readCalendar) return
            desk.calendarChanged()
        }
    }

    fun start() {
        scope.launch {
            desk.state.map { listOf(it.ready, it.settings.discoveryOn, it.settings.readCalendar, it.calendarGranted) }
                .distinctUntilChanged().collect { (ready, enabled, calendar, granted) ->
                    if (!ready) return@collect
                    val scheduler = context.getSystemService(JobScheduler::class.java)
                    if (enabled) {
                        if (scheduler.getPendingJob(JOB_ID) == null) {
                            val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, TaskBackgroundJob::class.java))
                                .setPeriodic(15 * 60_000L).setPersisted(true)
                                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresBatteryNotLow(true).build())
                            if (result != JobScheduler.RESULT_SUCCESS) desk.reportError("Android could not schedule task discovery. Open the app to retry.")
                        }
                    } else scheduler.cancel(JOB_ID)
                    val allowed = enabled && calendar && granted
                    if (allowed && !observing) {
                        runCatching { context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer) }
                            .onSuccess { observing = true }
                    } else if (!allowed && observing) {
                        context.contentResolver.unregisterContentObserver(observer)
                        observing = false
                    }
                }
        }
    }

    companion object { const val JOB_ID = 0x4D494B }
}

/** Keeps a background model pass inside the system job's bounded execution window. */
class TaskBackgroundJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val desk = (application as AgentApplication).graph.taskDesk
        work = scope.launch {
            try {
                withTimeoutOrNull(30_000) { while (!desk.state.value.ready) delay(200) }
                if (desk.state.value.ready && desk.state.value.settings.discoveryOn) {
                    val finished = withTimeoutOrNull(8 * 60_000L) {
                        do { desk.pulse(); if (!desk.hasBackgroundWork()) break; delay(2_000) } while (true)
                        true
                    } ?: false
                    if (!finished) desk.stopBackgroundTurns()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { desk.reportError("Background work: ${error.message}"); desk.stopBackgroundTurns() }
            finally { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        (application as AgentApplication).graph.scope.launch {
            (application as AgentApplication).graph.taskDesk.stopBackgroundTurns()
        }
        return true
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
