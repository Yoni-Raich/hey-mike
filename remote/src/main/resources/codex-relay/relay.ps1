# Hey Mike. Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
param([string]$Mode, [string]$Executable, [string]$Arguments = '--listen stdio://',
      [string]$Token, [string]$Metadata, [int]$Port, [long]$After = 0, [int]$LeaseSeconds = 300,
      [string]$LaunchConfig, [string]$Owner)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
Add-Type -Path (Join-Path $PSScriptRoot 'relay.cs')

# Ends a process only if its PID still has the recorded start time; a reused PID is never touched.
function Stop-Recorded($ProcessId, $StartedTicks) {
    if (-not $ProcessId -or -not $StartedTicks) { return }
    $process = $null
    try {
        $process = [Diagnostics.Process]::GetProcessById([int]$ProcessId)
        if (-not $process.HasExited -and $process.StartTime.ToUniversalTime().Ticks -eq [long]$StartedTicks) {
            $process.Kill(); $process.WaitForExit(5000) | Out-Null
        }
    } catch { }
    finally { if ($process) { $process.Dispose() } }
}

# One relay per owner. The owner is held by an earlier relay whose phone gave
# it up: that relay would keep the chat (and Codex's thread writer) for its
# whole offline lease. End its child, then its broker, and take the owner.
function Open-Owner([string]$Path) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    $previous = $Path + '.relay'
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $ended = $false
    while ($true) {
        try { $lock = [IO.File]::Open($Path, 'OpenOrCreate', 'ReadWrite', 'None'); break }
        catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw 'An earlier relay still owns this chat' }
            if (-not $ended -and (Test-Path -LiteralPath $previous)) {
                $ended = $true
                try {
                    $old = Get-Content -LiteralPath ([IO.File]::ReadAllText($previous).Trim()) -Raw | ConvertFrom-Json
                    if (-not $old.ended) {
                        Stop-Recorded $old.child $old.childTicks
                        Stop-Recorded $old.pid $old.startedTicks
                    }
                } catch { }
            }
            Start-Sleep -Milliseconds 250
        }
    }
    # Only the metadata path: it names no secret.
    [IO.File]::WriteAllText($previous, $Metadata)
    return $lock
}

if ($Mode -eq 'host') {
    $lease = [IO.File]::Open(($Metadata + '.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $ownerLock = $null
    try {
        if ($LaunchConfig) {
            $config = Get-Content -LiteralPath $LaunchConfig -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($config.owner) { $ownerLock = Open-Owner ([string]$config.owner) }
            $environment = @{}
            foreach ($property in $config.environment.PSObject.Properties) {
                $environment[$property.Name] = [string]$property.Value
            }
            [MikeCodexRelay]::Host([string]$config.executable,
                [MikeCodexRelay]::Arguments([string[]]$config.arguments), $Token, $Metadata,
                $LeaseSeconds, [string]$config.cwd, $environment)
        } else {
            if ($Owner) { $ownerLock = Open-Owner $Owner }
            [MikeCodexRelay]::Host($Executable, $Arguments, $Token, $Metadata, $LeaseSeconds)
        }
    }
    finally { if ($ownerLock) { $ownerLock.Dispose() }; $lease.Dispose() }
} elseif ($Mode -eq 'attach' -or $Mode -eq 'stop' -or $Mode -eq 'endpoint') {
    if ($Metadata) {
        $info = Get-Content -LiteralPath $Metadata -Raw | ConvertFrom-Json
        if ($info.ended -or $info.port -le 0) { [Console]::WriteLine('F Relay ended'); exit 1 }
        # Metadata can survive an abrupt Windows job termination. A reused PID
        # is not our broker. Reject it before the five-minute SSH retry loop.
        $broker = $null
        $alive = $false
        try {
            $broker = [Diagnostics.Process]::GetProcessById([int]$info.pid)
            $alive = -not $broker.HasExited -and (!$info.startedTicks -or
                $broker.StartTime.ToUniversalTime().Ticks -eq [long]$info.startedTicks)
        } catch { $alive = $false }
        finally { if ($broker) { $broker.Dispose() } }
        if (-not $alive) { [Console]::WriteLine('F Relay ended'); exit 1 }
        $Port = $info.port
    }
    if ($Mode -eq 'endpoint') { 'HEYMIKE {"port":' + $Port + '}' }
    elseif ($Mode -eq 'stop') { [MikeCodexRelay]::Stop($Port, $Token) }
    else { [MikeCodexRelay]::Attach($Port, $Token, $After) }
} else { throw 'Unknown Codex relay mode' }
