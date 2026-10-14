package dev.androidagent.remote

/** Resolve only saved computers; credentials and policy never come from tool arguments. */
class ChildSessionTargets(
    private val store: RemoteStore,
    private val recentProjects: () -> Map<String, List<String>> = { emptyMap() },
) {
    fun resolve(source: String, computer: String?, project: String?): RemoteBinding? {
        val inherited = store.binding(source)
        if (computer == null && project == null) return inherited?.copy(threadId = null, importedFromPc = false)
        if (computer.equals("phone", true)) {
            require(project == null) { "A phone chat has its own workspace; project selects a computer folder." }
            return null
        }
        val state = store.state.value
        val chosen = if (computer != null) {
            val matches = state.computers.firstOrNull { it.id == computer }?.let { listOf(it) }
                ?: state.computers.filter { it.label.equals(computer, true) }
            require(matches.size == 1) { "Choose a saved computer by its exact ID. Use computers status." }
            matches.single()
        } else inherited?.computerId?.let(store::computer) ?: state.defaultComputer
            ?: error("Choose a saved computer. Use computers status.")
        val asked = project ?: inherited?.cwd?.takeIf { inherited.computerId == chosen.id }
            ?: error("Give the project on ${chosen.label}.")
        val known = state.projects.filter { it.computerId == chosen.id }.map { it.path } +
            state.bindings.values.filter { it.computerId == chosen.id }.map { it.cwd } +
            recentProjects()[chosen.id].orEmpty()
        val matches = known.distinctBy { ComputerToolGateway.pathKey(it) }.filter {
            ComputerToolGateway.folderName(it).equals(asked, true) || ComputerToolGateway.pathKey(it) == ComputerToolGateway.pathKey(asked)
        }
        require(matches.size <= 1) { "More than one project has this name. Give the full folder path." }
        val path = matches.singleOrNull() ?: asked.takeIf { it.matches(Regex("^([A-Za-z]:[\\\\/]|/).*")) }
            ?: error("No saved project '$asked' on ${chosen.label}. Use computers status or give its full folder path.")
        return RemoteBinding(chosen.id, path)
    }
}
