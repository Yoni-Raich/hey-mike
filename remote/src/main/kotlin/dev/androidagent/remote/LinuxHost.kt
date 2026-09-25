package dev.androidagent.remote

import java.util.Base64

/** The system a computer runs, as found on its first connection. */
enum class HostOs(val label: String) {
    WINDOWS("Windows"),
    LINUX("Linux"),
    ;

    companion object {
        /**
         * Read the answer to `uname -s`. Windows has no `uname`, so its shell
         * fails the command; a Windows PC with Git's `uname` on its PATH says
         * MINGW or MSYS, which is still Windows.
         */
        fun fromUname(result: ExecResult): HostOs? {
            val name = result.stdout.trim().lineSequence().lastOrNull()?.trim().orEmpty()
            return when {
                result.exitCode != 0 -> WINDOWS
                name == "Linux" -> LINUX
                name == "Darwin" -> null
                else -> WINDOWS
            }
        }
    }
}

/** The commands Hey Mike runs on one kind of computer. Their answers read the same. */
interface HostScripts {
    fun probe(): String
    fun install(): String
    fun list(path: String, create: Boolean): String
    fun appServer(probe: HostProbe): String
}

/**
 * Everything Hey Mike runs on a Linux computer, as text.
 *
 * Each script is sent base64 encoded and decoded into POSIX `sh`, so no shell
 * quoting of the script itself is needed, whatever the login shell is. Like
 * the Windows scripts, each ends with one `HEYMIKE {json}` line.
 *
 * The app-server is the same pinned Linux package the phone itself runs
 * (`codex-app-server-package-<arch>-unknown-linux-musl`, checked against the
 * hashes the phone's build checks), unpacked under the user's
 * `~/.local/share/heymike`. It runs as that user with their own `~/.codex`.
 */
object LinuxHost : HostScripts {

    /** sha256 of `codex-app-server-package-<arch>-unknown-linux-musl.tar.gz`, rust-v0.156.0; the same pins as `tools/prepare_runtime.py`. */
    val PACKAGE_SHA256 = mapOf(
        "x86_64" to "037a10600af8228fca6f600ccd37048eb70b875df9097774fa9776f494ea55ea",
        "aarch64" to "817e464eec79ae7b3af56ea1395e4ddd58387e76e294bac0721dc58ceddec636",
    )

    private val PRELUDE = """
        set -e
        v='${WindowsHost.CODEX_VERSION}'
        case "${'$'}(uname -m)" in x86_64|amd64) arch=x86_64 ;; aarch64|arm64) arch=aarch64 ;; *) arch=unsupported ;; esac
        root="${'$'}{XDG_DATA_HOME:-${'$'}HOME/.local/share}/heymike/codex/${'$'}v"
        exe="${'$'}root/bin/codex-app-server"
        esc() { printf '%s' "${'$'}1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
    """.trimIndent()

    override fun probe(): String = wrap(
        """
        $PRELUDE
        installed=false; [ -x "${'$'}exe" ] && installed=true
        printf 'HEYMIKE {"computer":"%s","home":"%s","arch":"%s","exe":"%s","installed":%s,"shell":""}\n' "${'$'}(esc "${'$'}(hostname)")" "${'$'}(esc "${'$'}HOME")" "${'$'}arch" "${'$'}(esc "${'$'}exe")" "${'$'}installed"
        """.trimIndent(),
    )

    override fun install(): String = wrap(
        """
        $PRELUDE
        if [ "${'$'}arch" = unsupported ]; then echo "This computer's processor (${'$'}(uname -m)) is not supported by Codex." >&2; exit 1; fi
        if [ ! -x "${'$'}exe" ]; then
          case "${'$'}arch" in x86_64) sha='${PACKAGE_SHA256.getValue("x86_64")}' ;; aarch64) sha='${PACKAGE_SHA256.getValue("aarch64")}' ;; esac
          url="https://github.com/openai/codex/releases/download/rust-v${'$'}v/codex-app-server-package-${'$'}arch-unknown-linux-musl.tar.gz"
          tmp="${'$'}(mktemp -d)"
          if command -v curl >/dev/null 2>&1; then curl -fsSL "${'$'}url" -o "${'$'}tmp/p.tgz"
          elif command -v wget >/dev/null 2>&1; then wget -q "${'$'}url" -O "${'$'}tmp/p.tgz"
          else rm -rf "${'$'}tmp"; echo "Neither curl nor wget is installed on the computer." >&2; exit 1; fi
          got="${'$'}(sha256sum "${'$'}tmp/p.tgz" | cut -d' ' -f1)"
          if [ "${'$'}got" != "${'$'}sha" ]; then rm -rf "${'$'}tmp"; echo "The Codex download did not match its checksum (${'$'}got)." >&2; exit 1; fi
          mkdir -p "${'$'}tmp/x"; tar -xzf "${'$'}tmp/p.tgz" -C "${'$'}tmp/x"
          mkdir -p "${'$'}(dirname "${'$'}root")"; rm -rf "${'$'}root"; mv "${'$'}tmp/x" "${'$'}root"; rm -rf "${'$'}tmp"
        fi
        installed=false; [ -x "${'$'}exe" ] && installed=true
        printf 'HEYMIKE {"exe":"%s","installed":%s}\n' "${'$'}(esc "${'$'}exe")" "${'$'}installed"
        """.trimIndent(),
    )

    override fun list(path: String, create: Boolean): String {
        val encoded = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))
        val make = if (create) "mkdir -p -- \"${'$'}p\"" else ""
        return wrap(
            """
            $PRELUDE
            p="${'$'}(printf '%s' '$encoded' | base64 -d)"
            [ -z "${'$'}p" ] && p="${'$'}HOME"
            $make
            p="${'$'}(cd -- "${'$'}p" && pwd -P)"
            dirs=""; n=0
            for d in "${'$'}p"/*/; do
              [ -d "${'$'}d" ] || continue
              n=${'$'}((n + 1)); [ ${'$'}n -gt 500 ] && break
              dirs="${'$'}dirs${'$'}{dirs:+,}\"${'$'}(esc "${'$'}(basename "${'$'}d")")\""
            done
            parent="${'$'}(dirname "${'$'}p")"; [ "${'$'}p" = / ] && parent=""
            git=false; [ -e "${'$'}p/.git" ] && git=true
            printf 'HEYMIKE {"path":"%s","parent":"%s","dirs":[%s],"drives":[],"git":%s}\n' "${'$'}(esc "${'$'}p")" "${'$'}(esc "${'$'}parent")" "${'$'}dirs" "${'$'}git"
            """.trimIndent(),
        )
    }

    /** Single quotes keep a path with spaces whole in any POSIX login shell. */
    override fun appServer(probe: HostProbe): String {
        require('\'' !in probe.appServer) { "Unexpected quote in the Codex path" }
        return "'${probe.appServer}' --listen stdio://"
    }

    /** Run [script] in POSIX sh whatever the login shell, without quoting it. */
    internal fun wrap(script: String): String =
        "printf '%s' '" + Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8)) + "' | base64 -d | sh"
}
