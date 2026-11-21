package dev.androidagent.remote

import dev.androidagent.core.AgentModel
import dev.androidagent.core.EngineKind

internal object AntigravityModels {
    private const val PREFIX = "agy/"
    private val row = Regex("""^\s*([A-Za-z0-9][A-Za-z0-9._:/-]*)[ \t]{2,}(\S.*)$""")
    private val color = Regex("\u001b\\[[0-9;]*m")

    fun parse(output: String): List<AgentModel> = output.lineSequence().mapNotNull { line ->
        val match = row.matchEntire(line.replace(color, "").trimEnd()) ?: return@mapNotNull null
        val (slug, title) = match.destructured
        AgentModel(PREFIX + slug, title, engine = EngineKind.ANTIGRAVITY)
    }.distinctBy { it.id }.toList()

    fun slug(model: String?): String? =
        model?.takeUnless { it == "antigravity" }?.removePrefix(PREFIX)
}
