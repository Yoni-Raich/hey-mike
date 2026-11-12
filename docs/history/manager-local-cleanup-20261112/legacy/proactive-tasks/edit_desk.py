import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    assert s.count(a) == 1, a[:70]
    s = s.replace(a, b)


rep("""    private data class InFlight(val member: String, val batch: List<String>, val startedAt: Long, var seenActive: Boolean = false)""",
    """    private data class InFlight(
        val member: String,
        val batch: List<String>,
        val startedAt: Long,
        /** The chat's last outcome before this turn: a different one means this turn ran. */
        val previousRun: String?,
        var seenActive: Boolean = false,
    )""")
rep("""    fun toolRefusal(sessionId: String, name: String, arguments: JsonObject): String? =
        if (!ready) null else TaskRolePolicy.refusal(roleOf(sessionId), name, arguments)""",
    """    fun toolRefusal(sessionId: String, name: String, arguments: JsonObject): String? {
        if (ready) return TaskRolePolicy.refusal(roleOf(sessionId), name, arguments)
        // Before the team is loaded, a backstage chat is known by its name only;
        // refusing there is the safe side of not knowing yet.
        val title = sessions.sessions.value.firstOrNull { it.id == sessionId }?.title.orEmpty()
        return if (title.endsWith(BACKSTAGE_SUFFIX)) "The task board is still starting. Nothing ran." else null
    }""")
rep("""            else -> "${member.name} · backstage"
""", """            else -> "${member.name}$BACKSTAGE_SUFFIX"
""")
rep("""                ticker = launch { while (true) { runCatching { tick() }; delay(TICK_MS) } }""",
    """                // A backstage turn can be short: watch it start rather than hope a tick sees it.
                launch { runs().sessionStates.collect { states ->
                    inFlight.forEach { (session, flight) -> if (states[session]?.active == true) flight.seenActive = true }
                } }
                ticker = launch { while (true) { runCatching { tick() }; delay(TICK_MS) } }""")
rep("""        val messages = sessions.messages(sessionId).first()
            .filter""", """        // A chat's history is read from disk the first time; keep that off the main thread.
        val messages = kotlinx.coroutines.withContext(Dispatchers.IO) { sessions.messages(sessionId).first() }
            .filter""")
rep("""        inFlight[session] = InFlight(scout.id, plan.batch.map { it.id }, clock)""",
    """        inFlight[session] = InFlight(scout.id, plan.batch.map { it.id }, clock, runs().outcomeOf(session)?.runId)""")
rep("""                inFlight[session] = InFlight(coach.id, emptyList(), clock)""",
    """                inFlight[session] = InFlight(coach.id, emptyList(), clock, runs().outcomeOf(session)?.runId)""")
rep("""                inFlight[session] = InFlight(librarian.id, emptyList(), clock)""",
    """                inFlight[session] = InFlight(librarian.id, emptyList(), clock, runs().outcomeOf(session)?.runId)""")
rep("""            val started = flight.seenActive || clock - flight.startedAt > PASS_DEADLINE_MS
            if (!started) return@forEach
            val outcome = runs().outcomeOf(session)
            val ok = flight.seenActive && outcome != null && outcome.phase != RunPhase.ERROR && !outcome.outcomeUnknown""",
    """            val outcome = runs().outcomeOf(session)
            val ran = outcome != null && outcome.runId != flight.previousRun
            val started = flight.seenActive || ran || clock - flight.startedAt > PASS_DEADLINE_MS
            if (!started) return@forEach
            val ok = ran && outcome != null && outcome.phase != RunPhase.ERROR && !outcome.outcomeUnknown""")
rep("""        private const val PASS_DEADLINE_MS = 30 * 60_000L""", """        private const val PASS_DEADLINE_MS = 30 * 60_000L
        private const val BACKSTAGE_SUFFIX = " · backstage\"""")
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
