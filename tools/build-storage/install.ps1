param(
    [Parameter(Mandatory)][string]$Repository,
    [ValidateRange(1, 1000)][int]$MaxGiB = 5,
    [string]$GradleHome = (Join-Path $env:USERPROFILE '.gradle')
)
$ErrorActionPreference = 'Stop'
$Repository = [IO.Path]::GetFullPath($Repository).TrimEnd('\', '/')
$origin = & git -C $Repository remote get-url origin
if ($LASTEXITCODE -ne 0 -or $origin -notmatch 'github\.com[:/]Yoni-Raich/hey-mike(\.git)?$') {
    throw 'This installer is only for the Hey Mike repository.'
}
$homePath = Join-Path $GradleHome 'hey-mike-build-storage'
$initPath = Join-Path $GradleHome 'init.d'
[IO.Directory]::CreateDirectory($homePath) | Out-Null
[IO.Directory]::CreateDirectory($initPath) | Out-Null
foreach ($name in @('manage.ps1', 'stage_runtime.py')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $homePath $name) -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'hey-mike.init.gradle') -Destination (Join-Path $initPath 'hey-mike-build-storage.gradle') -Force
$settings = [ordered]@{
    repository = $Repository
    buildRoot = (Join-Path $Repository 'build')
    maxBytes = [long]$MaxGiB * 1GB
    policy = 'oldest-completed'
}
[IO.File]::WriteAllText((Join-Path $homePath 'config.json'), ($settings | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "Saved Hey Mike build storage: $($settings.buildRoot), $MaxGiB GiB, oldest completed first."
Write-Output 'Existing worktree build folders were not moved or removed.'
