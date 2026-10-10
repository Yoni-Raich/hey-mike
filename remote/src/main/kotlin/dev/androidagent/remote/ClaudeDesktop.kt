package dev.androidagent.remote

/** Official CLI handoff in the signed-in Windows desktop, not the SSH background session. */
object ClaudeDesktop {
    fun command(path: String): String = WindowsHost.powershell("& ${quote(path)}")

    private fun quote(value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' })
        return "'${value.replace("'", "''")}'"
    }

    fun script(claude: String, cwd: String, threadId: String, folder: String): String {
        ClaudeSessionFiles.requireId(threadId)
        val job = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}p = Start-Process -FilePath ${quote(claude)} -ArgumentList @('--desktop','--resume',${quote(threadId)}) -WorkingDirectory ${quote(cwd)} -WindowStyle Hidden -PassThru
                if (${'$'}p.WaitForExit(20000) -and ${'$'}p.ExitCode -ne 0) { throw 'Claude Desktop did not accept the handoff. Check that Desktop is installed and signed in.' }
                [IO.File]::WriteAllText(${'$'}env:HEYMIKE_DESKTOP_RESULT, '{"requested":true}')
            } catch { [IO.File]::WriteAllText(${'$'}env:HEYMIKE_DESKTOP_RESULT, (ConvertTo-Json @{error=${'$'}_.Exception.Message} -Compress)) }
        """.trimIndent()
        // Paths and result location are injected as literals into a one-off script.
        val resultName = "desktop-${java.util.UUID.randomUUID()}.json"
        val result = ClaudeLaunch.file(folder, resultName, HostOs.WINDOWS)
        val path = ClaudeLaunch.file(folder, "$resultName.ps1", HostOs.WINDOWS)
        val body = "${'$'}env:HEYMIKE_DESKTOP_RESULT = ${quote(result)}\n$job"
        val encoded = java.util.Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}taskName = 'HeyMikeClaude-' + [guid]::NewGuid().ToString()
            ${'$'}registered = ${'$'}false
            try {
                if (${'$'}env:CLAUDE_CONFIG_DIR -and [IO.Path]::GetFullPath(${'$'}env:CLAUDE_CONFIG_DIR).TrimEnd('\') -ne (Join-Path ${'$'}env:USERPROFILE '.claude')) {
                    throw 'Desktop handoff currently requires the default Claude Code session store. Custom stores can still be read and continued in Mike.'
                }
                ${'$'}interactive = @(Get-CimInstance Win32_Process -Filter "Name = 'explorer.exe'" | Where-Object {
                    ${'$'}owner = Invoke-CimMethod -InputObject ${'$'}_ -MethodName GetOwner
                    ${'$'}owner.User -eq ${'$'}env:USERNAME
                })
                if (${'$'}interactive.Count -eq 0) { throw 'Sign in to Windows on this computer before opening Claude Desktop.' }
                [IO.File]::WriteAllBytes(${quote(path)}, [Convert]::FromBase64String('$encoded'))
                ${'$'}action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + ${quote(path)} + '"')
                ${'$'}principal = New-ScheduledTaskPrincipal -UserId ${'$'}env:USERNAME -LogonType Interactive
                Register-ScheduledTask -TaskName ${'$'}taskName -Action ${'$'}action -Principal ${'$'}principal | Out-Null
                ${'$'}registered = ${'$'}true
                Start-ScheduledTask -TaskName ${'$'}taskName
                ${'$'}until = [DateTime]::UtcNow.AddSeconds(30)
                while (-not (Test-Path -LiteralPath ${quote(result)}) -and [DateTime]::UtcNow -lt ${'$'}until) { Start-Sleep -Milliseconds 200 }
                if (-not (Test-Path -LiteralPath ${quote(result)})) { throw 'Claude Desktop handoff did not report a result. Check the desktop before retrying.' }
                'HEYMIKE ' + [IO.File]::ReadAllText(${quote(result)})
            } catch { 'HEYMIKE ' + (ConvertTo-Json @{error=${'$'}_.Exception.Message} -Compress) }
            finally {
                if (${'$'}registered) { Unregister-ScheduledTask -TaskName ${'$'}taskName -Confirm:${'$'}false }
                Remove-Item -LiteralPath ${quote(path)},${quote(result)},${'$'}PSCommandPath -Force -ErrorAction SilentlyContinue
            }
        """.trimIndent()
    }
}
