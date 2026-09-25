package dev.androidagent.core

/** Cursor is scoped to an immutable catalog, never to a transient node address. */
internal class JevMenuCursor {
    private var version = ""
    private val seen = mutableSetOf<Int>()
    private var hops = 0
    fun visit(catalog: JevActionCatalog) {
        if (version != catalog.version) { version = catalog.version; seen.clear(); hops = 0 }
        seen += catalog.page
    }
    fun unseen(count: Int): Int? = (0 until count).firstOrNull { it !in seen }
    fun next(page: Int, count: Int): Int? {
        // One full wrap is allowed. Earlier pages remain selectable after the
        // first scan. Endless scans are a budget event, not proof of BLOCKED.
        if (++hops > 2 * count) return null
        return (page + 1) % count
    }
}
