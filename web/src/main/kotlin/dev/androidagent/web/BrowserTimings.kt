/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 */
package dev.androidagent.web

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Per-call measurements. Never stores URLs, selectors, field values or page text. */
internal class BrowserTimings(val operation: String, private val parent: BrowserTimings? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BrowserTimings> {
        private val ids = AtomicLong()
    }
    val id = ids.incrementAndGet()
    private val started = System.nanoTime()
    private val phases = linkedMapOf<String, Long>()
    private val commands = linkedMapOf<String, Command>()
    private val events = mutableListOf<JsonObject>()
    private val steps = mutableListOf<JsonObject>()
    private val counters = linkedMapOf<String, Int>()
    private data class Command(var calls: Int = 0, var ns: Long = 0, var maxNs: Long = 0, var errors: Int = 0, var timeouts: Int = 0)

    @Synchronized fun phase(name: String, ns: Long) { phases[name] = (phases[name] ?: 0) + ns.coerceAtLeast(0); parent?.phase(name, ns) }
    @Synchronized fun count(name: String) { counters[name] = (counters[name] ?: 0) + 1; parent?.count(name) }
    @Synchronized fun command(method: String, ns: Long, failure: Throwable?) {
        val sample = commands.getOrPut(method) { Command() }
        sample.calls++; sample.ns += ns; sample.maxNs = maxOf(sample.maxNs, ns)
        if (failure != null) sample.errors++
        if (failure is CdpException && failure.code == CdpClient.TIMEOUT_CODE) sample.timeouts++
        parent?.command(method, ns, failure)
    }
    @Synchronized fun event(name: String) {
        if (events.size < 48) events += buildJsonObject {
            put("event", name); put("atMs", ms(System.nanoTime() - started))
        }
    }
    private var errorType: String? = null
    /** The failure's stable error name: never page text, so the log can say why a call failed. */
    @Synchronized fun error(type: String) { if (errorType == null) errorType = type; parent?.error(type) }
    @Synchronized fun step(index: Int, timing: JsonObject, action: String, verification: String) {
        steps += buildJsonObject {
            put("step", index); put("action", action); put("verification", verification); put("timings", timing)
        }
    }
    @Synchronized fun json(status: String, detail: Boolean = false): JsonObject = buildJsonObject {
        val total = System.nanoTime() - started
        put("callId", id); put("operation", operation); put("status", status)
        errorType?.let { put("errorType", it) }
        put("backendMs", ms(total))
        // Exclusive phases add up; CDP durations below overlap these phases.
        put("phasesMs", buildJsonObject { phases.forEach { (name, ns) -> put(name, ms(ns)) } })
        put("otherMs", ms((total - phases.values.sum()).coerceAtLeast(0)))
        put("cdp", buildJsonObject {
            put("calls", commands.values.sumOf { it.calls })
            put("totalMs", ms(commands.values.sumOf { it.ns }))
            put("timeouts", commands.values.sumOf { it.timeouts })
            put("errors", commands.values.sumOf { it.errors })
            commands.maxByOrNull { it.value.maxNs }?.let { (method, s) ->
                put("slowestMethod", method); put("maxMs", ms(s.maxNs))
            }
        })
        if (detail) put("commands", buildJsonObject {
            commands.forEach { (method, s) -> put(method, buildJsonObject {
                put("calls", s.calls); put("totalMs", ms(s.ns)); put("maxMs", ms(s.maxNs))
                if (s.errors > 0) put("errors", s.errors)
                if (s.timeouts > 0) put("timeouts", s.timeouts)
            }) }
        })
        if (counters.isNotEmpty()) put("counters", buildJsonObject { counters.forEach { (name, count) -> put(name, count) } })
        if (events.isNotEmpty()) put("events", JsonArray(events.toList()))
        if (steps.isNotEmpty()) put("steps", JsonArray(steps.toList()))
    }
    private fun ms(ns: Long): Double = (ns / 100_000L) / 10.0
}

private class BrowserSpan : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BrowserSpan>
    var childrenNs = 0L
}

/** Context follows dispatchers. Child spans are subtracted, not counted twice. */
internal suspend fun <T> browserPhase(name: String, block: suspend () -> T): T {
    val timing = coroutineContext[BrowserTimings] ?: return block()
    val parent = coroutineContext[BrowserSpan]
    val span = BrowserSpan()
    val started = System.nanoTime()
    try { return withContext(span) { block() } }
    finally {
        val ns = System.nanoTime() - started
        parent?.let { it.childrenNs += ns }
        timing.phase(name, ns - span.childrenNs)
    }
}

/** Wrap the protocol without logging command arguments or replies. */
internal class TimedCdpConnection(private val delegate: CdpConnection) : CdpConnection by delegate {
    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val timing = coroutineContext[BrowserTimings] ?: return delegate.call(method, params, timeoutMs)
        val started = System.nanoTime()
        var failure: Throwable? = null
        try { return delegate.call(method, params, timeoutMs) }
        catch (error: Throwable) { failure = error; throw error }
        finally { timing.command(method, System.nanoTime() - started, failure) }
    }
}
