package dev.androidagent.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Optional executable portion of a learned skill. It can suppress suggestions, never grant an action. */
@Serializable
data class TaskSuggestionFilter(
    val kind: String? = null,
    val source: String? = null,
    val words: List<String> = emptyList(),
    val examples: List<Example> = emptyList(),
    val ownerRulesHash: String? = null,
) {
    @Serializable data class Example(val title: String, val kind: String? = null, val source: String? = null, val ignored: Boolean)
    fun ignores(title: String, taskKind: String?, taskSource: String?): Boolean =
        (kind == null || kind == taskKind) && (source == null || source == taskSource) &&
            words.all { title.contains(it, ignoreCase = true) }

    fun check() {
        require(kind != null || source != null || words.isNotEmpty()) { "A filter cannot match all suggestions." }
        require(words.size <= 8 && words.all { it.length in 2..60 }) { "Use up to eight specific phrases." }
        require(examples.size in 2..12 && examples.any { it.ignored } && examples.any { !it.ignored }) {
            "A filter needs a matching example and a counterexample."
        }
        require(examples.all { it.title.length in 2..200 && ignores(it.title, it.kind, it.source) == it.ignored }) {
            "The proposed filter failed its example checks. No rule was saved."
        }
    }
    companion object {
        private val block = Regex("```mike-filter\\s*\\n([\\s\\S]*?)```")
        private fun rulesHash(rules: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rules.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Bind tested filters to the current owner rules. Any owner edit invalidates old suppression. */
        fun stamp(content: String, ownerRules: String): String = block.replace(content) { match ->
            val rule = Json.decodeFromString<TaskSuggestionFilter>(match.groupValues[1]).also(TaskSuggestionFilter::check)
            "```mike-filter\n${Json.encodeToString(serializer(), rule.copy(ownerRulesHash = rulesHash(ownerRules)))}\n```"
        }
        fun active(content: String, ownerRules: String): List<TaskSuggestionFilter> =
            parse(content).filter { it.ownerRulesHash == rulesHash(ownerRules) }
        fun parse(content: String): List<TaskSuggestionFilter> = block.findAll(content).map {
            Json.decodeFromString<TaskSuggestionFilter>(it.groupValues[1]).also(TaskSuggestionFilter::check)
        }.toList().also { require(it.size <= 4) { "Use at most four filters in one file." } }
    }
}
