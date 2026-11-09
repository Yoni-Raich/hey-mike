param([string]$Payload)
# Append a chat's earlier turns to a Claude Code session transcript. No model or auth request.
# The payload file holds {cwd, sessionId, records}: records are JSON lines, the first
# naming "@HEYMIKE_LEAF@" as its parent. Same rules as the app's ClaudeTranscript.extend.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$utf8 = New-Object System.Text.UTF8Encoding($false)

function IsLink($item) { return ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }
function Field($row, [string]$name) {
    if ($null -eq $row) { return $null }
    $property = $row.PSObject.Properties[$name]
    if ($null -ne $property) { return $property.Value }
    return $null
}
function Leaf([string[]]$lines) {
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        try { $row = $lines[$i] | ConvertFrom-Json } catch { continue }
        $type = Field $row 'type'
        if ($type -in @('user','assistant','system','attachment') -and (Field $row 'isSidechain') -ne $true -and (Field $row 'uuid')) {
            return [string](Field $row 'uuid')
        }
    }
    return $null
}

try {
    $data = [IO.File]::ReadAllText($Payload, $utf8) | ConvertFrom-Json
    Remove-Item -LiteralPath $Payload -Force
    $session = [string]$data.sessionId
    if ($session -notmatch '^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$') { throw 'Invalid Claude session ID' }
    $records = @($data.records | ForEach-Object { [string]$_ })
    if ($records.Count -gt 0) {
        $config = if ($env:CLAUDE_CONFIG_DIR) { $env:CLAUDE_CONFIG_DIR } else { Join-Path $env:USERPROFILE '.claude' }
        $projects = Join-Path $config 'projects'
        if ((Test-Path -LiteralPath $projects) -and (IsLink (Get-Item -LiteralPath $projects))) { throw 'Claude projects directory is a symlink' }
        $target = $null
        if (Test-Path -LiteralPath $projects) {
            foreach ($folder in Get-ChildItem -LiteralPath $projects -Directory) {
                if (IsLink $folder) { continue }
                $candidate = Join-Path $folder.FullName ($session + '.jsonl')
                if ((Test-Path -LiteralPath $candidate -PathType Leaf) -and -not (IsLink (Get-Item -LiteralPath $candidate))) { $target = $candidate; break }
            }
        }
        if ($null -eq $target) {
            $name = [regex]::Replace([string]$data.cwd, '[^A-Za-z0-9]', '-')
            if ($name.Length -gt 200) { throw 'Working folder path is too long' }
            $folder = Join-Path $projects $name
            New-Item -ItemType Directory -Force -Path $folder | Out-Null
            $target = Join-Path $folder ($session + '.jsonl')
        }
        $existing = if (Test-Path -LiteralPath $target) { [IO.File]::ReadAllText($target, $utf8) } else { '' }
        $lines = @($existing -split "`n" | ForEach-Object { $_.TrimEnd("`r") } | Where-Object { $_.Trim() })
        $parent = Leaf $lines
        $parentJson = if ($parent) { '"' + $parent + '"' } else { 'null' }
        $records[0] = $records[0].Replace('"@HEYMIKE_LEAF@"', $parentJson)
        $text = ($records -join "`n") + "`n"
        if ($existing.Length -gt 0 -and -not $existing.EndsWith("`n")) { $text = "`n" + $text }
        [IO.File]::AppendAllText($target, $text, $utf8)
    }
    $encoded = ConvertTo-Json -InputObject @{ok=$true} -Compress
} catch {
    $encoded = ConvertTo-Json -InputObject @{error=$_.Exception.Message} -Compress
}
'HEYMIKE ' + $encoded
