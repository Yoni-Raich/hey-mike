$ErrorActionPreference = 'Stop'
$env:ANDROID_HOME = 'C:\Users\SHIRA\AppData\Local\Android\Sdk'
Set-Location -LiteralPath 'C:\Users\SHIRA\.codex\worktrees\assistant-flow-limits-20261028'
& .\gradlew.bat --gradle-user-home 'C:/Users/SHIRA/AppData/Local/Temp/hey-mike/proactive-tasks/validation/gradle-home' --init-script 'C:/Users/SHIRA/AppData/Local/Temp/hey-mike/assistant-flow-limits-20261028/storage.gradle' :core:test :workspace:testDebugUnitTest :engine-codex:testDebugUnitTest :engine-claude:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --max-workers=2 --console=plain '-Pkotlin.compiler.execution.strategy=in-process' *> (Join-Path $PSScriptRoot 'build.log')
$taskResult = $LASTEXITCODE
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'build-exit.txt') -Value $taskResult
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build.log') -Tail 55
exit $taskResult
