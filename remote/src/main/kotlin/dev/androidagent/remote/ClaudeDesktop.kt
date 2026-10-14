package dev.androidagent.remote

import java.util.Base64

/**
 * The handoff ran, but whether Desktop opened the session is not known: no
 * answer came back. It must not be tried again on its own.
 */
class ClaudeDesktopUnknownException(message: String) : IllegalStateException(message)

/** Official CLI handoff in the signed-in Windows desktop, not the SSH background session. */
object ClaudeDesktop {
    fun command(path: String): String = WindowsHost.powershell("& ${quote(path)}")

    private fun quote(value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' })
        return "'${value.replace("'", "''")}'"
    }

    /**
     * A PowerShell expression for any text, spelled in ASCII. Windows
     * PowerShell reads a script file without a byte order mark in the
     * computer's ANSI code page, so a Hebrew folder written as a plain
     * literal arrives as other letters.
     */
    internal fun literal(value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' })
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        return "([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded')))"
    }

    /** UTF-8 with a byte order mark, as both script files are written. */
    fun bytes(script: String): ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + script.toByteArray(Charsets.UTF_8)

    fun script(claude: String, cwd: String, threadId: String, folder: String): String {
        ClaudeSessionFiles.requireId(threadId)
        val resultName = "desktop-${java.util.UUID.randomUUID()}.json"
        val result = ClaudeLaunch.file(folder, resultName, HostOs.WINDOWS)
        val path = ClaudeLaunch.file(folder, "$resultName.ps1", HostOs.WINDOWS)
        // Process.Start, not Start-Process: PowerShell would read `[` in a
        // path as a wildcard. Shell execution also starts an npm claude.cmd.
        val job = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}result = ${literal(result)}
            try {
                ${'$'}cwd = ${literal(cwd)}
                if (-not [IO.Directory]::Exists(${'$'}cwd)) { throw ('The folder of this session is no longer on the computer: ' + ${'$'}cwd) }
                ${'$'}info = New-Object Diagnostics.ProcessStartInfo
                ${'$'}info.FileName = ${literal(claude)}
                ${'$'}info.Arguments = '--desktop --resume $threadId'
                ${'$'}info.WorkingDirectory = ${'$'}cwd
                ${'$'}info.UseShellExecute = ${'$'}true
                ${'$'}info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
                ${'$'}p = [Diagnostics.Process]::Start(${'$'}info)
                if (${'$'}p -and ${'$'}p.WaitForExit(20000) -and ${'$'}p.ExitCode -ne 0) { throw 'Claude Desktop did not accept the handoff. Check that Desktop is installed and signed in.' }
                [IO.File]::WriteAllText(${'$'}result, '{"requested":true}')
            } catch { [IO.File]::WriteAllText(${'$'}result, (ConvertTo-Json @{error=${'$'}_.Exception.Message} -Compress)) }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(bytes(job))
        val bridge = """
            ${'$'}ErrorActionPreference = 'Stop'
            function HeyMikeAscii([string]${'$'}text) { [regex]::Replace(${'$'}text, '[^\x00-\x7F]', { param(${'$'}m) '\u{0:x4}' -f [int][char]${'$'}m.Value }) }
            ${'$'}taskName = 'HeyMikeClaude-' + [guid]::NewGuid().ToString()
            ${'$'}registered = ${'$'}false
            ${'$'}started = ${'$'}false
            ${'$'}result = ${literal(result)}
            ${'$'}job = ${literal(path)}
            try {
                if (${'$'}env:CLAUDE_CONFIG_DIR -and [IO.Path]::GetFullPath(${'$'}env:CLAUDE_CONFIG_DIR).TrimEnd('\') -ne (Join-Path ${'$'}env:USERPROFILE '.claude')) {
                    throw 'Desktop handoff currently requires the default Claude Code session store. Custom stores can still be read and continued in Mike.'
                }
                ${'$'}interactive = @(Get-CimInstance Win32_Process -Filter "Name = 'explorer.exe'" | Where-Object {
                    ${'$'}owner = Invoke-CimMethod -InputObject ${'$'}_ -MethodName GetOwner
                    ${'$'}owner.User -eq ${'$'}env:USERNAME
                })
                if (${'$'}interactive.Count -eq 0) { throw 'Sign in to Windows on this computer before opening Claude Desktop.' }
                [IO.File]::WriteAllBytes(${'$'}job, [Convert]::FromBase64String('$encoded'))
                ${'$'}action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + ${'$'}job + '"')
                ${'$'}principal = New-ScheduledTaskPrincipal -UserId ${'$'}env:USERNAME -LogonType Interactive
                Register-ScheduledTask -TaskName ${'$'}taskName -Action ${'$'}action -Principal ${'$'}principal | Out-Null
                ${'$'}registered = ${'$'}true
                Start-ScheduledTask -TaskName ${'$'}taskName
                ${'$'}started = ${'$'}true
                ${'$'}until = [DateTime]::UtcNow.AddSeconds(30)
                while (-not (Test-Path -LiteralPath ${'$'}result) -and [DateTime]::UtcNow -lt ${'$'}until) { Start-Sleep -Milliseconds 200 }
                if (-not (Test-Path -LiteralPath ${'$'}result)) { throw 'Claude Desktop handoff did not report a result.' }
                'HEYMIKE ' + (HeyMikeAscii ([IO.File]::ReadAllText(${'$'}result)))
            } catch {
                ${'$'}failure = @{error=${'$'}_.Exception.Message}
                # Once the task started, Desktop may have opened even without an answer.
                if (${'$'}started) { ${'$'}failure.code = 'unknown' }
                'HEYMIKE ' + (HeyMikeAscii (ConvertTo-Json ${'$'}failure -Compress))
            }
            finally {
                if (${'$'}registered) { Unregister-ScheduledTask -TaskName ${'$'}taskName -Confirm:${'$'}false -ErrorAction SilentlyContinue }
                Remove-Item -LiteralPath ${'$'}job,${'$'}result,${'$'}PSCommandPath -Force -ErrorAction SilentlyContinue
            }
        """.trimIndent()
        check(bridge.all { it.code < 128 } && job.all { it.code < 128 }) { "Desktop handoff script is not ASCII" }
        return bridge
    }
}
