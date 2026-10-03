package dev.androidagent.remote

import dev.androidagent.core.AgentModel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/** Where a computer keeps its own Claude Code, as a probe found it. */
data class ClaudeProbe(
    /** The `claude` program, or null when the computer has none. */
    val path: String?,
    val version: String,
    val home: String,
)

/**
 * A computer's own Claude Code, as the app last saw it.
 *
 * It is the user's install and the user's sign-in on that computer. Mike
 * starts it over SSH and never copies a sign-in to it or from it, so a
 * computer where `claude` is missing or signed out offers no Claude models
 * until the user fixes that there.
 */
data class ComputerClaude(
    val installed: Boolean,
    val version: String = "",
    val signedIn: Boolean = false,
    /** The account `claude auth status` names, when signed in. */
    val account: String = "",
    val models: List<AgentModel> = emptyList(),
) {
    /** A chat on this computer can run on Claude. */
    val ready: Boolean get() = installed && signedIn
}

/**
 * Finding and starting the computer's Claude Code, as text.
 *
 * A launch goes through a small script file put beside Mike's other files on
 * the computer, never through a long command line: the arguments include an
 * empty string and paths with spaces, and the three shells a command may land
 * in (cmd, PowerShell, a POSIX shell) each quote those differently. The
 * command itself is then only the script's path, which all of them take.
 */
object ClaudeLaunch {
    private const val MARKER = "HEYMIKE "

    /** Prints `HEYMIKE {"claude":…,"version":…,"home":…}`; `claude` is empty when it is not installed. */
    fun probe(os: HostOs): String = if (os == HostOs.LINUX) LinuxHost.wrap(LINUX_PROBE) else WindowsHost.powershell(WINDOWS_PROBE)

    // A command run over SSH gets a bare PATH, without what the user's shell
    // profile adds, so the usual install places are tried by name, and a
    // login shell is asked last.
    private val LINUX_PROBE = """
        esc() { printf '%s' "${'$'}1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
        p="${'$'}(command -v claude 2>/dev/null || true)"
        for c in "${'$'}HOME/.local/bin/claude" "${'$'}HOME/.claude/local/claude" /usr/local/bin/claude /opt/homebrew/bin/claude; do
          [ -z "${'$'}p" ] && [ -x "${'$'}c" ] && p="${'$'}c"
        done
        [ -z "${'$'}p" ] && command -v bash >/dev/null 2>&1 && p="${'$'}(bash -lc 'command -v claude' 2>/dev/null || true)"
        v=""
        [ -n "${'$'}p" ] && v="${'$'}("${'$'}p" --version 2>/dev/null | head -n 1 || true)"
        printf 'HEYMIKE {"claude":"%s","version":"%s","home":"%s"}\n' "${'$'}(esc "${'$'}p")" "${'$'}(esc "${'$'}v")" "${'$'}(esc "${'$'}HOME")"
    """.trimIndent()

    private val WINDOWS_PROBE = """
        ${'$'}ProgressPreference='SilentlyContinue'
        ${'$'}c=''
        try { ${'$'}c=(Get-Command claude -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source } catch {}
        foreach (${'$'}p in @((Join-Path ${'$'}env:USERPROFILE '.local\bin\claude.exe'), (Join-Path ${'$'}env:APPDATA 'npm\claude.cmd'))) {
          if (-not ${'$'}c -and (Test-Path -LiteralPath ${'$'}p)) { ${'$'}c=${'$'}p }
        }
        ${'$'}v=''
        if (${'$'}c) { try { ${'$'}v=[string](& ${'$'}c --version 2>${'$'}null | Select-Object -First 1) } catch {} }
        '$MARKER' + (ConvertTo-Json -Compress -InputObject ([ordered]@{claude=[string]${'$'}c; version=${'$'}v; home=${'$'}env:USERPROFILE}))
    """.trimIndent()

    fun parseProbe(result: ExecResult): ClaudeProbe {
        val o = WindowsHost.payload(result)
        fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        return ClaudeProbe(path = text("claude").takeIf { it.isNotEmpty() }, version = text("version"), home = text("home"))
    }

    /** The folder Mike's launch files go in, under the user's home on the computer. */
    fun folder(home: String, os: HostOs): String =
        if (os == HostOs.LINUX) home.trimEnd('/') + "/.hey-mike/claude" else home.trimEnd('\\') + "\\.hey-mike\\claude"

    /** [name] inside [folder], in the computer's own spelling. */
    fun file(folder: String, name: String, os: HostOs): String = folder + (if (os == HostOs.LINUX) "/" else "\\") + name

    fun scriptName(base: String, os: HostOs): String = base + if (os == HostOs.LINUX) ".sh" else ".cmd"

    /**
     * A script that runs `claude` with [args] in [cwd] (the home folder when
     * null) with [env] set, and leaves the process's own stdin and stdout to
     * it. Every value is quoted for the script's own language; one that cannot
     * be quoted there is refused rather than passed through.
     */
    fun script(os: HostOs, claude: String, cwd: String?, env: Map<String, String>, args: List<String>): String =
        if (os == HostOs.LINUX) posixScript(claude, cwd, env, args) else batchScript(claude, cwd, env, args)

    private fun posixScript(claude: String, cwd: String?, env: Map<String, String>, args: List<String>): String = buildString {
        append("#!/bin/sh\n")
        if (cwd != null) append("cd -- ").append(sh(cwd)).append(" || exit 1\n")
        env.forEach { (key, value) ->
            require(ENV_NAME.matches(key)) { "Unexpected environment name" }
            append("export ").append(key).append('=').append(sh(value)).append('\n')
        }
        append("exec ").append((listOf(claude) + args).joinToString(" ", transform = ::sh)).append('\n')
    }

    private fun sh(value: String): String {
        require('\u0000' !in value && '\n' !in value) { "Unexpected character in a Claude argument" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    // UTF-8 from the second line on, so a folder or user name outside the
    // computer's own code page still reads right.
    private fun batchScript(claude: String, cwd: String?, env: Map<String, String>, args: List<String>): String = buildString {
        append("@echo off\r\n")
        append("chcp 65001 >nul\r\n")
        if (cwd != null) append("cd /d ").append(bat(cwd)).append("\r\nif errorlevel 1 exit /b 1\r\n")
        env.forEach { (key, value) ->
            require(ENV_NAME.matches(key)) { "Unexpected environment name" }
            append("set ").append(bat("$key=$value")).append("\r\n")
        }
        append((listOf(claude) + args).joinToString(" ", transform = ::bat)).append("\r\n")
    }

    private fun bat(value: String): String {
        require(value.none { it == '"' || it == '\n' || it == '\r' || it == '\u0000' }) { "Unexpected character in a Claude argument" }
        // Inside quotes only the percent sign still means something to a batch file.
        return "\"" + value.replace("%", "%%") + "\""
    }

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The command that runs the script at [scriptPath], whatever shell the computer hands it to. */
    fun command(os: HostOs, scriptPath: String, powerShellDefault: Boolean): String {
        require('\'' !in scriptPath && '"' !in scriptPath) { "Unexpected quote in the launch path" }
        return when {
            os == HostOs.LINUX -> "sh '$scriptPath'"
            powerShellDefault -> "& '$scriptPath'"
            else -> "\"$scriptPath\""
        }
    }

    /** For tests: the text of a wrapped Linux script. */
    internal fun unwrap(command: String): String =
        String(Base64.getDecoder().decode(command.substringAfter("'%s' '").substringBefore("'")), Charsets.UTF_8)
}
