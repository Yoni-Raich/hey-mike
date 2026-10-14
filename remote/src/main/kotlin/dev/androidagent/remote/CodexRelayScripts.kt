package dev.androidagent.remote

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** Application-owned helper files only; no transcript or auth token is written to disk. */
internal object CodexRelayScripts {
    data class Instance(val token: String, val folder: String) {
        val script get() = "$folder/relay.ps1"
        val metadata get() = "$folder/connection.json"
    }

    suspend fun install(link: SshLink, probe: HostProbe, scratch: File): Instance {
        val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val instance = Instance(token, probe.home.replace('\\', '/') + "/.hey-mike/codex-relay/$token")
        val local = File(scratch, token).apply { check(mkdirs() || isDirectory) { "Could not prepare Codex relay" } }
        try {
            for (name in listOf("relay.cs", "relay.ps1")) {
                val source = File(local, name)
                CodexRelayScripts::class.java.getResourceAsStream("/codex-relay/$name").use { resource ->
                    checkNotNull(resource) { "Codex relay helper is missing from this build" }
                    source.outputStream().use { resource.copyTo(it) }
                }
                link.upload(source, "${instance.folder}/$name", replace = false)
            }
        } finally {
            // These two files and this directory were created above for this instance.
            listOf("relay.cs", "relay.ps1").forEach { File(local, it).delete() }
            local.delete()
        }
        return instance
    }

    /** No sign-in data is included; callers supply only process arguments and launch settings. */
    fun launchConfig(exe: String, args: List<String>, cwd: String, env: Map<String, String>, owner: String? = null): String {
        require((listOf(exe, cwd) + args + env.values).none { '\u0000' in it })
        require(env.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
        return buildJsonObject {
            put("executable", exe)
            put("arguments", buildJsonArray { args.forEach { add(it) } })
            put("cwd", cwd)
            owner?.let { put("owner", it) }
            put("environment", buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
        }.toString()
    }

    fun start(instance: Instance, exe: String? = null): String {
        val launch = if (exe == null) "-LaunchConfig \"${instance.folder}/launch.json\"" else "-Executable \"$exe\""
        require('"' !in (exe ?: instance.folder))
        return WindowsHost.powershell("""
        ${'$'}ErrorActionPreference='Stop'
        ${'$'}script=${quote(instance.script)}
        ${'$'}metadata=${quote(instance.metadata)}
        ${'$'}arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + ${'$'}script + '" -Mode host ' + ${quote(launch)} + ' -Token ${instance.token} -Metadata "' + ${'$'}metadata + '"'
        Add-Type -Path (Join-Path (Split-Path -Parent ${'$'}script) 'relay.cs')
        ${'$'}started=[MikeCodexRelay]::StartDetached((Get-Command powershell.exe).Source, ${'$'}arguments)
        ${'$'}deadline=[DateTime]::UtcNow.AddSeconds(20)
        do {
            if (Test-Path -LiteralPath ${'$'}metadata) {
                try {
                    ${'$'}ready=Get-Content -LiteralPath ${'$'}metadata -Raw | ConvertFrom-Json
                    if (${'$'}ready.port -gt 0 -and ${'$'}ready.pid -eq ${'$'}started -and
                        [MikeCodexRelay]::Ready([int]${'$'}ready.port, '${instance.token}')) {
                        'HEYMIKE {"ready":true}'; exit 0
                    }
                    if (${'$'}ready.ended) { throw 'Codex relay ended during startup' }
                } catch { if ([DateTime]::UtcNow -ge ${'$'}deadline) { throw } }
            }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt ${'$'}deadline)
        throw 'Codex relay did not start'
    """.trimIndent())
    }

    fun attach(instance: Instance, after: Long): String {
        require(after >= 0)
        return WindowsHost.powershell("""
            & ${quote(instance.script)} -Mode attach -Token '${instance.token}' -Metadata ${quote(instance.metadata)} -After $after
        """.trimIndent())
    }

    fun endpoint(instance: Instance): String = WindowsHost.powershell("""
        & ${quote(instance.script)} -Mode endpoint -Metadata ${quote(instance.metadata)}
    """.trimIndent())

    fun attach(link: SshLink, instance: Instance, after: Long): Process {
        require(after >= 0)
        val result = link.run(endpoint(instance), 30_000)
        if (result.stdout.lineSequence().any { it.trim() == "F Relay ended" }) {
            throw CodexRelayProcess.RelayEnded("Codex relay ended. Task outcome is unknown.")
        }
        check(result.exitCode == 0) { "Could not read the retained relay endpoint" }
        val port = WindowsHost.payload(result)["port"]?.toString()?.toIntOrNull()
        check(port != null && port in 1..65535) { "Invalid retained relay endpoint" }
        val process = link.loopback(port)
        try {
            process.outputStream.write("H1 ${instance.token} $after\n".toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            return process
        } catch (error: Exception) {
            process.destroy()
            throw error
        }
    }

    private fun quote(text: String): String = "'" + text.replace("'", "''") + "'"
}
