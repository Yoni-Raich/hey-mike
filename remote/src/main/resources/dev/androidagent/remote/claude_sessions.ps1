param([ValidateSet('list','read','busy')][string]$Mode, [string]$SessionId)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$uuidPattern = '^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$'
$configPath = if ($env:CLAUDE_CONFIG_DIR) { $env:CLAUDE_CONFIG_DIR } else { Join-Path $env:USERPROFILE '.claude' }
$projectsPath = Join-Path $configPath 'projects'

function Field($row, [string]$name) {
    if ($null -eq $row) { return $null }
    $property = $row.PSObject.Properties[$name]
    if ($null -ne $property) { return $property.Value }
    return $null
}
function IsLink($file) { return ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }
function ContentText($content) {
    if ($content -is [string]) { return $content }
    $parts = @($content | Where-Object { (Field $_ 'type') -eq 'text' -and (Field $_ 'text') -is [string] } | ForEach-Object { Field $_ 'text' })
    return $parts -join "`n"
}
function Stamp($value) {
    try { return [DateTimeOffset]::Parse([string]$value).ToUnixTimeMilliseconds() } catch { return 0L }
}
function TranscriptFiles {
    if ((Test-Path -LiteralPath $configPath) -and (IsLink (Get-Item -LiteralPath $configPath))) { throw 'Claude configuration directory is a symlink' }
    if (-not (Test-Path -LiteralPath $projectsPath)) { return @() }
    if (IsLink (Get-Item -LiteralPath $projectsPath)) { throw 'Claude projects directory is a symlink' }
    $found = New-Object 'System.Collections.Generic.List[object]'
    foreach ($folder in Get-ChildItem -LiteralPath $projectsPath -Directory) {
        if (IsLink $folder) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $folder.FullName -File -Filter '*.jsonl') {
            if ($file.BaseName -match $uuidPattern -and -not (IsLink $file)) { [void]$found.Add($file) }
            if ($found.Count -gt 10000) { throw 'Too many Claude transcripts; narrow the project inventory' }
        }
    }
    return @($found | Sort-Object LastWriteTimeUtc -Descending)
}
function BusyIds {
    $ids = @{}
    $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    foreach ($process in $processes) {
        if ($process.Name -notmatch 'claude|node') { continue }
        $command = [string]$process.CommandLine
        if ($command -notmatch '(?i)claude') { continue }
        foreach ($match in [regex]::Matches($command, '(?:--resume|--session-id|-r)(?:\s+|=)["'']?([0-9a-fA-F-]{36})(?:["''\s]|$)')) {
            if ($match.Groups[1].Value -match $uuidPattern) { $ids[$match.Groups[1].Value] = $true }
        }
    }
    $sessionsPath = Join-Path $configPath 'sessions'
    if (Test-Path -LiteralPath $sessionsPath) {
        if (-not (IsLink (Get-Item -LiteralPath $sessionsPath))) {
            foreach ($file in Get-ChildItem -LiteralPath $sessionsPath -File -Filter '*.json') {
                if ((IsLink $file) -or $file.Length -gt 65536) { continue }
                try {
                    $row = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
                    $ownerPid = Field $row 'pid'
                    if (-not $ownerPid) { $ownerPid = $file.BaseName }
                    $sid = Field $row 'sessionId'
                    if (-not $sid) { $sid = Field $row 'session_id' }
                    if ($sid -match $uuidPattern -and @($processes | Where-Object { $_.ProcessId -eq [int]$ownerPid }).Count -gt 0) { $ids[$sid] = $true }
                } catch { }
            }
        }
    }
    return $ids
}
function SliceRows($file) {
    $stream = [IO.File]::Open($file.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $length = [Math]::Min(524288L, $stream.Length)
        $bytes = New-Object byte[] ([int]$length)
        [void]$stream.Read($bytes, 0, $bytes.Length)
        $head = [Text.Encoding]::UTF8.GetString($bytes)
        if ($stream.Length -gt 524288) {
            [void]$stream.Seek([Math]::Max(524288L, $stream.Length - 524288L), [IO.SeekOrigin]::Begin)
            $tailBytes = New-Object byte[] ([int]($stream.Length - $stream.Position))
            [void]$stream.Read($tailBytes, 0, $tailBytes.Length)
            $tail = [Text.Encoding]::UTF8.GetString($tailBytes)
            $head = $head.Substring(0, [Math]::Max(0, $head.LastIndexOf("`n")))
            $break = $tail.IndexOf("`n")
            $tail = if ($break -ge 0) { $tail.Substring($break + 1) } else { '' }
            $head += "`n" + $tail
        }
        foreach ($line in $head.Split("`n")) {
            try { $row = $line | ConvertFrom-Json; if ($null -ne $row) { $row } } catch { }
        }
    } finally { $stream.Dispose() }
}
function Metadata($file, $busy) {
    $cwd = ''; $custom = ''; $title = ''; $summary = ''; $first = ''; $model = ''
    foreach ($row in SliceRows $file) {
        if ((Field $row 'isSidechain') -or (Field $row 'teamName')) { continue }
        if (Field $row 'cwd') { $cwd = [string](Field $row 'cwd') }
        switch (Field $row 'type') {
            'custom-title' { if (Field $row 'customTitle') { $custom = [string](Field $row 'customTitle') } }
            'ai-title' { if (Field $row 'aiTitle') { $title = [string](Field $row 'aiTitle') } }
            'summary' { if (Field $row 'summary') { $summary = [string](Field $row 'summary') } }
            'user' { if (-not $first -and -not (Field $row 'isMeta')) { $first = ContentText (Field (Field $row 'message') 'content'); if ($first.Length -gt 140) { $first = $first.Substring(0,140) } } }
            'assistant' { if (Field (Field $row 'message') 'model') { $model = [string](Field (Field $row 'message') 'model') } }
        }
    }
    if ($cwd -notmatch '^(?:[A-Za-z]:[\\/]|/|\\\\)') { return $null }
    $name = if ($custom) { $custom } elseif ($title) { $title } else { $summary }
    $label = if ($name) { $name } elseif ($first) { $first } else { 'Claude conversation' }
    if ($label.Length -gt 200) { $label = $label.Substring(0,200) }
    if ($name.Length -gt 200) { $name = $name.Substring(0,200) }
    return [ordered]@{id=$file.BaseName;cwd=$cwd;title=$label;name=$(if ($name) {$name} else {$null});updatedAt=([DateTimeOffset]$file.LastWriteTimeUtc).ToUnixTimeMilliseconds();model=$(if ($model) {$model} else {$null});busy=$busy.ContainsKey($file.BaseName)}
}
function Messages($file) {
    if ($file.Length -gt 268435456) { throw 'Claude transcript exceeds the 256 MiB read limit' }
    $stream = [IO.File]::Open($file.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $completeTail = $false
    if ($stream.Length -gt 0) { [void]$stream.Seek(-1, [IO.SeekOrigin]::End); $completeTail = $stream.ReadByte() -eq 10; [void]$stream.Seek(0, [IO.SeekOrigin]::Begin) }
    $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
    $nodes = @{}; $leaf = $null; $order = 0
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($stream.Position -gt 268435456) { throw 'Claude transcript exceeds the 256 MiB read limit' }
            if ($reader.EndOfStream -and -not $completeTail) { break }
            if ([Text.Encoding]::UTF8.GetByteCount($line) -gt 33554432) { throw 'Claude transcript record exceeds the 32 MiB limit' }
            if (-not $line.Trim()) { continue }
            if ($line.Contains([char]0)) { throw 'Malformed complete record in Claude transcript' }
            try { $row = $line | ConvertFrom-Json } catch {
                throw 'Malformed complete record in Claude transcript'
            }
            if (Field $row 'isSidechain') { continue }
            $uid = Field $row 'uuid'
            if ($uid -isnot [string]) { continue }
            # Forked ancestors may keep their original sessionId. Compaction
            # uses parentUuid; following logicalParentUuid can create a cycle.
            $parent = Field $row 'parentUuid'
            $role = Field $row 'type'; $text = ''
            if ($role -notin @('user','assistant','progress','system','attachment')) { continue }
            $main = -not (Field $row 'isMeta') -and -not (Field $row 'teamName')
            if ($role -in @('user','assistant') -and $main) { $text = ContentText (Field (Field $row 'message') 'content'); $leaf = $uid }
            $order++
            $nodes[$uid] = @{id=$uid;parent=$parent;role=$role;text=$text;main=$main;order=$order;createdAt=(Stamp (Field $row 'timestamp'))}
        }
    } finally { $reader.Dispose() }
    $parents = @{}
    foreach ($node in $nodes.Values) { if ($node.parent) { $parents[$node.parent] = $true } }
    $best = -1
    foreach ($terminal in @($nodes.Keys)) {
        if ($parents.ContainsKey($terminal)) { continue }
        $cursor = $terminal; $seen = @{}
        while ($cursor -and $nodes.ContainsKey($cursor) -and -not $seen.ContainsKey($cursor)) {
            $seen[$cursor] = $true; $node = $nodes[$cursor]
            if ($node.role -in @('user','assistant')) {
                if ($node.main -and $node.order -gt $best) { $best = $node.order; $leaf = $cursor }
                break
            }
            $cursor = $node.parent
        }
    }
    $chain = New-Object 'System.Collections.Generic.List[object]'; $visited = @{}
    while ($leaf -and $nodes.ContainsKey($leaf)) {
        if ($visited.ContainsKey($leaf)) { throw 'Claude transcript has a parent cycle' }
        $visited[$leaf] = $true; $node = $nodes[$leaf]
        if ($node.role -in @('user','assistant') -and $node.text) { [void]$chain.Add([ordered]@{id=$node.id;role=$node.role;text=$node.text;createdAt=$node.createdAt}) }
        $leaf = $node.parent
    }
    if ($leaf) { throw 'Claude history has a missing parent record; its source transcript is required' }
    $chain.Reverse()
    return @($chain.ToArray())
}
try {
    $busy = BusyIds
    $files = @(TranscriptFiles)
    if ($Mode -eq 'list') {
        $items = @($files | ForEach-Object { $item = Metadata $_ $busy; if ($null -ne $item) { $item } })
        $counts = @{}
        foreach ($item in $items) { $counts[$item.id] = 1 + $counts[$item.id] }
        $items = @($items | Where-Object { $counts[$_.id] -eq 1 })
        $payload = @{threads=$items;skipped=($files.Count - $items.Count)}
    } else {
        if ($SessionId -notmatch $uuidPattern) { throw 'Invalid Claude session ID' }
        $selected = @($files | Where-Object { $_.BaseName -eq $SessionId })
        if ($selected.Count -eq 0) { throw 'Claude session was not found; its original transcript is required' }
        if ($selected.Count -ne 1) { throw 'Claude session ID occurs in more than one project; resolve the duplicate first' }
        $item = Metadata $selected[0] $busy
        if ($null -eq $item) { throw 'Claude session has no valid working folder' }
        if ($Mode -eq 'busy') { $payload = @{thread=$item;busy=$busy.ContainsKey($SessionId)} }
        else { $payload = @{thread=$item;messages=@(Messages $selected[0]);busy=$busy.ContainsKey($SessionId)} }
    }
    $encoded = ConvertTo-Json -InputObject $payload -Compress -Depth 12
    if ([Text.Encoding]::UTF8.GetByteCount($encoded) -gt 8388608) { throw 'Claude history exceeds the 8 MiB display limit' }
} catch { $encoded = ConvertTo-Json -InputObject @{error=$_.Exception.Message} -Compress }
'HEYMIKE ' + $encoded
