package dev.androidagent.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** What a probe learned about a Windows computer. */
data class WindowsProbe(
    val computerName: String,
    val home: String,
    val arch: String,
    /** Where the pinned app-server lives, installed or not. */
    val appServer: String,
    val installed: Boolean,
    /** OpenSSH's DefaultShell is PowerShell rather than cmd. */
    val powerShellDefault: Boolean,
)

/** One folder level, for the picker. */
data class FolderListing(
    val path: String,
    val parent: String?,
    val folders: List<String>,
    val drives: List<String>,
    /** The folder is a git repository root. */
    val isGitRepo: Boolean,
)

/**
 * Everything Hey Mike runs on a Windows computer, as text.
 *
 * Every script goes through `powershell.exe -EncodedCommand`, which reads the
 * same under OpenSSH's default cmd shell and under PowerShell, and needs no
 * quoting of the script itself. Scripts end with one `HEYMIKE {json}` line so
 * their result is found among whatever else a profile or module prints.
 *
 * The app-server is the official Codex release pinned for the phone, fetched
 * by the computer from GitHub and checked against the hashes below before it
 * is unpacked. It runs as the signed-in user with their own `~/.codex`, so
 * their sign-in, config, skills and MCP servers are the ones Codex uses.
 */
object WindowsHost {
    const val CODEX_VERSION = "0.156.0"

    /** sha256 of `codex-app-server-package-<arch>-pc-windows-msvc.tar.gz`, rust-v0.156.0. */
    val PACKAGE_SHA256 = mapOf(
        "x86_64" to "3502ed0a2ba1491a1823011b41432e0cbcef420c74e042e285e766def6157ea9",
        "aarch64" to "34cab8a89783a7f50302caa74d43928b4c2bec127fbbb7470f6d17e52a88b4db",
    )

    private const val MARKER = "HEYMIKE "

    private val PRELUDE = """
        ${'$'}ProgressPreference='SilentlyContinue'
        ${'$'}ErrorActionPreference='Stop'
        ${'$'}v='$CODEX_VERSION'
        ${'$'}arch=if (${'$'}env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {'aarch64'} else {'x86_64'}
        ${'$'}root=Join-Path ${'$'}env:LOCALAPPDATA "HeyMike\codex\${'$'}v"
        ${'$'}exe=Join-Path ${'$'}root 'bin\codex-app-server.exe'
    """.trimIndent()

    fun probeScript(): String = """
        $PRELUDE
        ${'$'}shell=''
        try { ${'$'}shell=(Get-ItemProperty -Path 'HKLM:\SOFTWARE\OpenSSH' -Name DefaultShell -ErrorAction Stop).DefaultShell } catch {}
        ${'$'}o=[ordered]@{computer=${'$'}env:COMPUTERNAME; home=${'$'}env:USERPROFILE; arch=${'$'}arch; exe=${'$'}exe; installed=(Test-Path -LiteralPath ${'$'}exe); shell=${'$'}shell}
        '$MARKER' + (ConvertTo-Json -Compress -InputObject ${'$'}o)
    """.trimIndent()

    fun installScript(): String = """
        $PRELUDE
        [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
        ${'$'}sha=@{x86_64='${PACKAGE_SHA256.getValue("x86_64")}';aarch64='${PACKAGE_SHA256.getValue("aarch64")}'}[${'$'}arch]
        if (-not (Test-Path -LiteralPath ${'$'}exe)) {
          ${'$'}tmp=Join-Path ${'$'}env:TEMP "heymike-codex-${'$'}v-${'$'}arch.tar.gz"
          Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/openai/codex/releases/download/rust-v${'$'}v/codex-app-server-package-${'$'}arch-pc-windows-msvc.tar.gz" -OutFile ${'$'}tmp
          ${'$'}got=(Get-FileHash -Algorithm SHA256 -LiteralPath ${'$'}tmp).Hash.ToLower()
          if (${'$'}got -ne ${'$'}sha) { Remove-Item -LiteralPath ${'$'}tmp -Force; throw "The Codex download did not match its checksum (${'$'}got)." }
          ${'$'}stage=${'$'}root + '.part'
          if (Test-Path -LiteralPath ${'$'}stage) { Remove-Item -LiteralPath ${'$'}stage -Recurse -Force }
          New-Item -ItemType Directory -Force -Path ${'$'}stage | Out-Null
          tar.exe -xzf ${'$'}tmp -C ${'$'}stage
          if (${'$'}LASTEXITCODE -ne 0) { throw "Unpacking Codex failed (tar exit ${'$'}LASTEXITCODE)." }
          Remove-Item -LiteralPath ${'$'}tmp -Force
          if (Test-Path -LiteralPath ${'$'}root) { Remove-Item -LiteralPath ${'$'}root -Recurse -Force }
          Move-Item -LiteralPath ${'$'}stage -Destination ${'$'}root
        }
        '$MARKER' + (ConvertTo-Json -Compress -InputObject ([ordered]@{exe=${'$'}exe; installed=(Test-Path -LiteralPath ${'$'}exe)}))
    """.trimIndent()

    /** List the folders in [path], or in the user's home folder when it is blank. */
    /** List the folders in [path]; with [create], make the folder first. */
    fun listScript(path: String, create: Boolean = false): String {
        val encoded = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))
        val make = if (create) "New-Item -ItemType Directory -Force -Path ${'$'}p | Out-Null" else ""
        return """
            ${'$'}ErrorActionPreference='Stop'
            ${'$'}p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded'))
            if (${'$'}p -eq '') { ${'$'}p=${'$'}env:USERPROFILE }
            $make
            ${'$'}p=(Resolve-Path -LiteralPath ${'$'}p).ProviderPath
            ${'$'}d=@(Get-ChildItem -LiteralPath ${'$'}p -Directory -Force -ErrorAction SilentlyContinue | Where-Object { -not (${'$'}_.Attributes -band [IO.FileAttributes]::Hidden) } | Sort-Object Name | Select-Object -First 500 | ForEach-Object { ${'$'}_.Name })
            ${'$'}r=@(Get-PSDrive -PSProvider FileSystem | ForEach-Object { ${'$'}_.Root })
            ${'$'}o=[ordered]@{path=${'$'}p; parent=(Split-Path -Parent ${'$'}p); dirs=${'$'}d; drives=${'$'}r; git=(Test-Path -LiteralPath (Join-Path ${'$'}p '.git'))}
            '$MARKER' + (ConvertTo-Json -Compress -InputObject ${'$'}o)
        """.trimIndent()
    }

    /** A command line that runs [script] under either Windows default shell. */
    fun powershell(script: String): String {
        val utf16 = script.toByteArray(Charsets.UTF_16LE)
        return "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
            Base64.getEncoder().encodeToString(utf16)
    }

    /**
     * Start the app-server on stdio. The path is quoted for spaces in the user
     * name; cmd keeps a quoted executable path as it is, and PowerShell needs
     * the call operator to run a quoted path rather than print it.
     */
    fun appServerCommand(probe: WindowsProbe): String {
        require('"' !in probe.appServer && '\'' !in probe.appServer) { "Unexpected quote in the Codex path" }
        return if (probe.powerShellDefault) "& '${probe.appServer}' --listen stdio://"
        else "\"${probe.appServer}\" --listen stdio://"
    }

    fun parseProbe(result: ExecResult): WindowsProbe {
        val o = payload(result)
        val shell = o.text("shell").orEmpty().lowercase()
        return WindowsProbe(
            computerName = o.text("computer").orEmpty(),
            home = o.text("home").orEmpty(),
            arch = o.text("arch").orEmpty(),
            appServer = o.text("exe") ?: error("The computer did not say where Codex goes"),
            installed = (o["installed"] as? JsonPrimitive)?.booleanOrNull == true,
            powerShellDefault = shell.endsWith("powershell.exe") || shell.endsWith("pwsh.exe"),
        )
    }

    fun parseInstall(result: ExecResult): Boolean =
        (payload(result)["installed"] as? JsonPrimitive)?.booleanOrNull == true

    fun parseListing(result: ExecResult): FolderListing {
        val o = payload(result)
        return FolderListing(
            path = o.text("path") ?: error("No folder in the answer"),
            parent = o.text("parent")?.takeIf { it.isNotBlank() },
            folders = o.strings("dirs"),
            drives = o.strings("drives"),
            isGitRepo = (o["git"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** The `HEYMIKE` line, or the script's own error in one readable line. */
    internal fun payload(result: ExecResult): JsonObject {
        val line = result.stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(MARKER) }
        if (line == null) {
            val reason = powershellError(result.stderr).ifBlank { result.stdout.trim().take(300) }
            error(reason.ifBlank { "The computer ran the command but gave no answer (exit ${result.exitCode})." })
        }
        return Json.parseToJsonElement(line.removePrefix(MARKER)).jsonObject
    }

    /**
     * PowerShell writes errors to a redirected stderr as CLIXML. Pull out the
     * error text so the user reads a sentence, not XML.
     */
    internal fun powershellError(stderr: String): String {
        if (!stderr.trimStart().startsWith("#< CLIXML")) return stderr.trim().take(500)
        return Regex("""<S S="Error">(.*?)</S>""", RegexOption.DOT_MATCHES_ALL).findAll(stderr)
            .joinToString("") { it.groupValues[1] }
            .replace("_x000D_", "").replace("_x000A_", " ")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** PowerShell writes a one-element array as a bare value; accept both. */
    private fun JsonObject.strings(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        else -> emptyList()
    }
}
