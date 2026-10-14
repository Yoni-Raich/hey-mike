# Hey Mike. Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
param([string]$Mode, [string]$Executable, [string]$Arguments = '--listen stdio://',
      [string]$Token, [string]$Metadata, [int]$Port, [long]$After = 0, [int]$LeaseSeconds = 300,
      [string]$LaunchConfig)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
Add-Type -Path (Join-Path $PSScriptRoot 'relay.cs')
if ($Mode -eq 'host') {
    $lease = [IO.File]::Open(($Metadata + '.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $owner = $null
    try {
        if ($LaunchConfig) {
            $config = Get-Content -LiteralPath $LaunchConfig -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($config.owner) {
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([string]$config.owner)) | Out-Null
                $owner = [IO.File]::Open([string]$config.owner, 'OpenOrCreate', 'ReadWrite', 'None')
            }
            $environment = @{}
            foreach ($property in $config.environment.PSObject.Properties) {
                $environment[$property.Name] = [string]$property.Value
            }
            [MikeCodexRelay]::Host([string]$config.executable,
                [MikeCodexRelay]::Arguments([string[]]$config.arguments), $Token, $Metadata,
                $LeaseSeconds, [string]$config.cwd, $environment)
        } else { [MikeCodexRelay]::Host($Executable, $Arguments, $Token, $Metadata, $LeaseSeconds) }
    }
    finally { if ($owner) { $owner.Dispose() }; $lease.Dispose() }
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
