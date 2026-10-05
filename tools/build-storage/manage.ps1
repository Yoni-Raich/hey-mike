param(
    [ValidateSet('begin', 'finish', 'status', 'cleanup')][string]$Mode = 'status',
    [string]$Config = (Join-Path $PSScriptRoot 'config.json'),
    [string]$Worktree,
    [string]$Bucket
)
$ErrorActionPreference = 'Stop'

function FullPath([string]$Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}
function AssertPlainPath([string]$Path) {
    $current = FullPath $Path
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Build storage refuses a link or junction: $current"
            }
        }
        $parent = [IO.Directory]::GetParent($current)
        if (-not $parent) { break }
        $current = $parent.FullName
    }
}
function TreeBytes([string]$Path) {
    AssertPlainPath $Path
    [long]$total = 0
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($Path)
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Build storage refuses a link or junction: $($item.FullName)"
            }
            if ($item.PSIsContainer) { $pending.Push($item.FullName) }
            else { $total += $item.Length }
        }
    }
    return $total
}
function BucketId([string]$Path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes((FullPath $Path).ToLowerInvariant())
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').Substring(0, 16).ToLowerInvariant()
    } finally { $sha.Dispose() }
}
function SaveJson($Value, [string]$Path) {
    $temp = "$Path.tmp"
    [IO.File]::WriteAllText($temp, ($Value | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $Path -Force
}
function CommonGit([string]$Path) {
    $result = & git -C $Path rev-parse --path-format=absolute --git-common-dir 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Not a Git worktree: $Path" }
    return FullPath $result
}

$settings = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
$repo = FullPath $settings.repository
$root = FullPath $settings.buildRoot
if ($root -ne (FullPath (Join-Path $repo 'build')) -or $settings.maxBytes -le 0 -or $settings.policy -ne 'oldest-completed') {
    throw 'Invalid Hey Mike build storage configuration.'
}
AssertPlainPath $root
[IO.Directory]::CreateDirectory($root) | Out-Null
$globalLock = $null
for ($attempt = 0; $attempt -lt 150 -and -not $globalLock; $attempt++) {
    try { $globalLock = [IO.File]::Open((Join-Path $root '.storage.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { Start-Sleep -Milliseconds 100 }
}
if (-not $globalLock) { throw 'Build storage is busy. Try again.' }
try {
    if ($Mode -in @('begin', 'finish')) {
        if ((CommonGit $Worktree) -ne (CommonGit $repo)) { throw 'Worktree is outside the Hey Mike repository.' }
        if ($Bucket -ne (BucketId $Worktree)) { throw 'Worktree build ID mismatch.' }
        $bucketPath = FullPath (Join-Path $root $Bucket)
        if ([IO.Directory]::GetParent($bucketPath).FullName -ne $root) { throw 'Bucket escapes build storage.' }
        AssertPlainPath $bucketPath
        [IO.Directory]::CreateDirectory($bucketPath) | Out-Null
        $markerPath = Join-Path $bucketPath '.hey-mike-build.json'
        if (Test-Path -LiteralPath $markerPath) {
            $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
            if ($marker.schema -ne 1 -or $marker.worktree -ne (FullPath $Worktree) -or $marker.repository -ne $repo) {
                throw 'Build bucket belongs to a different worktree.'
            }
        } else {
            if (@(Get-ChildItem -LiteralPath $bucketPath -Force).Count -gt 0) {
                throw 'Refusing to adopt an existing unmarked build folder.'
            }
            $marker = [pscustomobject]@{ schema = 1; repository = $repo; worktree = (FullPath $Worktree); lastFinished = $null; startingUntil = $null }
        }
        if (-not ($marker.PSObject.Properties.Name -contains 'startingUntil')) { $marker | Add-Member startingUntil $null }
        if ($Mode -eq 'finish') {
            $marker.lastFinished = [DateTime]::UtcNow.ToString('o')
            $marker.startingUntil = $null
        } else {
            # Reserve the bucket across the short gap before Java takes its
            # long-lived lease. An interrupted start expires after 10 minutes.
            $marker.startingUntil = [DateTime]::UtcNow.AddMinutes(10).ToString('o')
        }
        SaveJson $marker $markerPath
    }

    [long]$bytes = TreeBytes $root
    $removed = @()
    if ($Mode -in @('begin', 'finish', 'cleanup')) {
        $candidates = @()
        foreach ($dir in Get-ChildItem -LiteralPath $root -Directory -Force) {
            if ($dir.Name -notmatch '^[a-f0-9]{16}$' -or $dir.Name -eq $Bucket) { continue }
            $markerPath = Join-Path $dir.FullName '.hey-mike-build.json'
            if (-not (Test-Path -LiteralPath $markerPath)) { continue }
            $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
            if ($marker.schema -ne 1 -or $marker.repository -ne $repo -or $dir.Name -ne (BucketId $marker.worktree) -or -not $marker.lastFinished) { continue }
            if ($marker.startingUntil -and [DateTime]::Parse($marker.startingUntil).ToUniversalTime() -gt [DateTime]::UtcNow) { continue }
            $candidates += [pscustomobject]@{ Path = $dir.FullName; Date = [DateTime]::Parse($marker.lastFinished).ToUniversalTime() }
        }
        foreach ($candidate in $candidates | Sort-Object Date, Path) {
            if ($bytes -le $settings.maxBytes) { break }
            $target = FullPath $candidate.Path
            if ([IO.Directory]::GetParent($target).FullName -ne $root -or $target -eq $root) { throw 'Refusing cleanup outside build storage.' }
            $lease = $null
            try {
                # Java's Gradle lease and this exclusive handle prevent deletion
                # while another build uses the same bucket.
                $lease = [IO.File]::Open((Join-Path $target '.active.lock'), 'OpenOrCreate', 'ReadWrite', 'Delete')
            } catch [IO.IOException] { continue }
            try {
                $size = TreeBytes $target
                Remove-Item -LiteralPath $target -Recurse -Force
                $removed += $target
                $bytes -= $size
            } finally { $lease.Dispose() }
        }
    }
    [pscustomobject]@{ root = $root; bytes = $bytes; maxBytes = [long]$settings.maxBytes; overLimit = ($bytes -gt $settings.maxBytes); removed = $removed } | ConvertTo-Json -Compress
    if ($bytes -gt $settings.maxBytes) { Write-Warning 'Storage is above its target: active/current or unmanaged files were kept.' }
} finally { $globalLock.Dispose() }
