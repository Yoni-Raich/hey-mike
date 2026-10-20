<#
Hey Mike worktree helper. Two modes:

  status  Lists every worktree of this repository with its state (dirty files,
          commits that exist on no remote, merged or not, build bucket size,
          active build) plus orphan build buckets and stray folders.
  remove  Closes one worktree safely: refuses on uncommitted or unpushed work,
          a lock, an active build, or the current folder; then deletes its build
          bucket, removes the worktree, prunes Git metadata and, with
          -DeleteBranch, deletes the local branch only if it is merged.

  powershell -NoProfile -File worktree.ps1 status
  powershell -NoProfile -File worktree.ps1 remove -Path <worktree> [-DeleteBranch] [-DiscardChanges]

-DiscardChanges removes a worktree that still has uncommitted or unpushed work.
Pass it only after the user has said in this task that this work may be lost.
#>
param(
    [Parameter(Position = 0)][ValidateSet('status', 'remove')][string]$Mode = 'status',
    [string]$Path,
    [switch]$DeleteBranch,
    [switch]$DiscardChanges
)
$ErrorActionPreference = 'Stop'

function FullPath([string]$P) { return [IO.Path]::GetFullPath($P).TrimEnd('\', '/') }
function RunGit([string]$Dir, [string[]]$GitArgs) {
    # Windows PowerShell turns native stderr into a terminating error under Stop.
    $ErrorActionPreference = 'Continue'
    $out = & git.exe -C $Dir @GitArgs 2>$null
    return [pscustomobject]@{ Ok = ($LASTEXITCODE -eq 0); Out = @($out) }
}
function BucketId([string]$P) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes((FullPath $P).ToLowerInvariant())
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').Substring(0, 16).ToLowerInvariant()
    } finally { $sha.Dispose() }
}
function TreeBytes([string]$P) {
    if (-not (Test-Path -LiteralPath $P)) { return 0 }
    $sum = (Get-ChildItem -LiteralPath $P -Recurse -Force -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($sum) { return [long]$sum } else { return 0 }
}
function Size([long]$B) {
    if ($B -ge 1GB) { return '{0:N1} GB' -f ($B / 1GB) }
    if ($B -ge 1MB) { return '{0:N0} MB' -f ($B / 1MB) }
    return '{0:N0} KB' -f ($B / 1KB)
}
# A running Gradle build holds .active.lock open for writing; an exclusive
# open then fails. Same test the build-storage manager uses.
function BuildActive([string]$Bucket) {
    $lock = Join-Path $Bucket '.active.lock'
    if (-not (Test-Path -LiteralPath $lock)) { return $false }
    try { $h = [IO.File]::Open($lock, 'Open', 'ReadWrite', 'None'); $h.Dispose(); return $false }
    catch [IO.IOException] { return $true }
}

$common = (RunGit (Get-Location).Path @('rev-parse', '--path-format=absolute', '--git-common-dir'))
if (-not $common.Ok) { throw 'Run this from inside the Hey Mike repository or one of its worktrees.' }
$repo = FullPath (Split-Path -Parent ($common.Out[0]))
$buildRoot = Join-Path $repo 'build'

function Worktrees {
    $list = @(); $cur = $null
    foreach ($line in (RunGit $repo @('worktree', 'list', '--porcelain')).Out) {
        if ($line -like 'worktree *') { if ($cur) { $list += $cur }; $cur = [ordered]@{ Path = (FullPath $line.Substring(9)); Branch = $null; Locked = $false; Prunable = $false } }
        elseif ($line -like 'branch refs/heads/*') { $cur.Branch = $line.Substring(18) }
        elseif ($line -like 'locked*') { $cur.Locked = $true }
        elseif ($line -like 'prunable*') { $cur.Prunable = $true }
    }
    if ($cur) { $list += $cur }
    return $list
}
function Inspect($W) {
    $p = $W.Path
    $exists = Test-Path -LiteralPath $p
    $dirty = if ($exists) { @((RunGit $p @('status', '--porcelain')).Out | Where-Object { $_ }) } else { @() }
    $unpushed = if ($exists) { @((RunGit $p @('rev-list', 'HEAD', '--not', '--remotes')).Out | Where-Object { $_ }) } else { @() }
    $merged = $false
    if ($exists) {
        foreach ($base in 'origin/dev', 'origin/main') {
            if ((RunGit $p @('merge-base', '--is-ancestor', 'HEAD', $base)).Ok) { $merged = $base; break }
        }
    }
    $bucket = Join-Path $buildRoot (BucketId $p)
    return [pscustomobject]@{
        Path = $p; Branch = $W.Branch; Exists = $exists; Locked = $W.Locked; Prunable = $W.Prunable
        Dirty = $dirty; Unpushed = $unpushed; Merged = $merged
        Bucket = $bucket; BucketBytes = (TreeBytes $bucket); BuildActive = (BuildActive $bucket)
    }
}

if ($Mode -eq 'status') {
    $all = Worktrees
    $known = @{}
    foreach ($w in $all) {
        $known[$w.Path.ToLowerInvariant()] = $true
        $i = Inspect $w
        $flags = @()
        if ($i.Path -eq $repo) { $flags += 'MAIN CHECKOUT (never remove)' }
        if (-not $i.Exists) { $flags += 'FOLDER MISSING (git worktree prune)' }
        if ($i.Locked) { $flags += 'LOCKED' }
        if ($i.Dirty.Count) { $flags += "$($i.Dirty.Count) uncommitted" }
        if ($i.Unpushed.Count) { $flags += "$($i.Unpushed.Count) commits on no remote" }
        if ($i.Merged) { $flags += "merged into $($i.Merged)" }
        elseif ($i.Exists -and -not $i.Unpushed.Count) { $flags += 'pushed, not merged' }
        if ($i.BuildActive) { $flags += 'BUILD RUNNING' }
        $branch = if ($i.Branch) { $i.Branch } else { '(detached)' }
        Write-Output "$($i.Path)"
        Write-Output "    branch: $branch | build: $(Size $i.BucketBytes) | $($flags -join ' | ')"
    }
    Write-Output ''
    Write-Output 'Build buckets with no registered worktree (safe to delete when no build runs):'
    $found = $false
    foreach ($dir in Get-ChildItem -LiteralPath $buildRoot -Directory -Force -ErrorAction SilentlyContinue) {
        $marker = Join-Path $dir.FullName '.hey-mike-build.json'
        $owner = if (Test-Path -LiteralPath $marker) { (Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json).worktree } else { '(no marker)' }
        if ($owner -ne '(no marker)' -and $known.ContainsKey((FullPath $owner).ToLowerInvariant())) { continue }
        $found = $true
        Write-Output "    $($dir.FullName)  $(Size (TreeBytes $dir.FullName))  was: $owner"
    }
    if (-not $found) { Write-Output '    none' }
    Write-Output ''
    Write-Output 'Folders in worktree homes that Git does not know (ask the user before deleting):'
    $found = $false
    foreach ($wtHome in (Join-Path $env:USERPROFILE '.codex\worktrees'), (Join-Path $repo '.claude\worktrees')) {
        foreach ($dir in Get-ChildItem -LiteralPath $wtHome -Directory -Force -ErrorAction SilentlyContinue) {
            if ($known.ContainsKey((FullPath $dir.FullName).ToLowerInvariant())) { continue }
            # Codex keeps checkouts of other repositories here too; only list ours.
            $c = RunGit $dir.FullName @('rev-parse', '--path-format=absolute', '--git-common-dir')
            if ($c.Ok -and (FullPath (Split-Path -Parent $c.Out[0])) -ne $repo) { continue }
            $found = $true
            Write-Output "    $($dir.FullName)"
        }
    }
    if (-not $found) { Write-Output '    none' }
    return
}

# ---- remove ----
if (-not $Path) { throw 'remove needs -Path <worktree folder>.' }
$target = FullPath $Path
if ($target -eq $repo) { throw 'Refusing: that is the main checkout, not a worktree.' }
$entry = Worktrees | Where-Object { $_.Path -eq $target } | Select-Object -First 1
if (-not $entry) { throw "Refusing: $target is not a registered worktree of $repo. Run 'status'." }
$here = FullPath (Get-Location).Path
if ($here -eq $target -or $here.StartsWith($target + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing: the shell is inside that worktree. cd to the main checkout first.'
}
$i = Inspect $entry
if ($i.Locked) { throw 'Refusing: the worktree is locked (git worktree lock). Ask the user why before unlocking.' }
if ($i.BuildActive) { throw 'Refusing: a Gradle build is running in that worktree. Wait for it or stop it first.' }
if (($i.Dirty.Count -or $i.Unpushed.Count) -and -not $DiscardChanges) {
    $msg = "Refusing: work would be lost.`n"
    if ($i.Dirty.Count) { $msg += "Uncommitted:`n  " + ($i.Dirty -join "`n  ") + "`n" }
    if ($i.Unpushed.Count) { $msg += "Commits on no remote: " + ($i.Unpushed -join ', ') + "`n" }
    $msg += 'Commit and push, or get the user''s OK and pass -DiscardChanges.'
    throw $msg
}

# 1. Build bucket: only the one whose marker names this worktree.
if (Test-Path -LiteralPath $i.Bucket) {
    $marker = Join-Path $i.Bucket '.hey-mike-build.json'
    $owner = if (Test-Path -LiteralPath $marker) { FullPath (Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json).worktree } else { $null }
    if ($owner -ne $target) { throw "Refusing: $($i.Bucket) is not marked as this worktree's bucket." }
    $lease = [IO.File]::Open((Join-Path $i.Bucket '.active.lock'), 'OpenOrCreate', 'ReadWrite', 'Delete')
    try { Remove-Item -LiteralPath $i.Bucket -Recurse -Force } finally { $lease.Dispose() }
    Write-Output "Deleted build bucket $($i.Bucket) ($(Size $i.BucketBytes))."
}

# 2. The worktree itself.
$gitArgs = @('worktree', 'remove')
if ($DiscardChanges) { $gitArgs += '--force' }
$gitArgs += $target
$r = RunGit $repo $gitArgs
if (Test-Path -LiteralPath $target) {
    # Git can drop its metadata yet fail to delete ignored files (.gradle, local
    # build output) that Windows still has open.
    try { Remove-Item -LiteralPath $target -Recurse -Force }
    catch {
        throw "Could not delete $target : $($_.Exception.Message)`nA process still has files open there (a Gradle or Kotlin daemon, an IDE, a shell). See the skill's 'Locked files' section, then run remove again."
    }
}
RunGit $repo @('worktree', 'prune') | Out-Null
Write-Output "Removed worktree $target."

# 3. Local branch, only if fully merged. Remote branches are never touched.
if ($DeleteBranch -and $i.Branch) {
    $d = RunGit $repo @('branch', '-d', $i.Branch)
    if ($d.Ok) { Write-Output "Deleted local branch $($i.Branch)." }
    else { Write-Output "Kept local branch $($i.Branch): not merged into its upstream or HEAD. Delete it with -D only if the user agrees." }
}
