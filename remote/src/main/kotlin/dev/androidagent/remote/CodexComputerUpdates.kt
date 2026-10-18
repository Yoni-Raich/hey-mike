package dev.androidagent.remote

import java.util.Base64

/** The updater runs on the computer's own connection and never stops its live agent. */
internal object CodexComputerUpdates {
    const val SCRIPT_NAME = "codex-runtime-updater-v1.py"
    fun source(): String = CodexComputerUpdates::class.java.getResourceAsStream("/codex_runtime.py")
        ?.bufferedReader()?.use { it.readText() } ?: error("Codex updater is missing")

    fun command(os: HostOs, path: String): String {
        val encoded = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))
        return if (os == HostOs.WINDOWS) WindowsHost.powershell("""
            ${'$'}p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded'))
            if (Get-Command python -ErrorAction SilentlyContinue) { & python ${'$'}p --existing-only }
            elseif (Get-Command py -ErrorAction SilentlyContinue) { & py -3 ${'$'}p --existing-only }
            else { 'HEYMIKE {"installed":false,"updateMessage":"Automatic Codex updates need Python 3.9 or later on this computer"}' }
        """.trimIndent()) else LinuxHost.wrap("""
            p="${'$'}(printf '%s' '$encoded' | base64 -d)"
            if command -v python3 >/dev/null 2>&1; then python3 "${'$'}p" --existing-only
            else printf '%s\n' 'HEYMIKE {"installed":false,"updateMessage":"Automatic Codex updates need Python 3.9 or later on this computer"}'; fi
        """.trimIndent())
    }
}

data class CodexComputerVersion(
    val selected: String,
    val running: String? = null,
    val updateMessage: String? = null,
    val automatic: Boolean = false,
)
