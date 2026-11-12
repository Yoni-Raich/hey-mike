$ErrorActionPreference = 'Stop'
$env:ANDROID_HOME = 'C:\Users\SHIRA\AppData\Local\Android\Sdk'
Set-Location -LiteralPath 'C:\Users\SHIRA\.codex\worktrees\personal-assistant-flow-20261028'
& .\gradlew.bat --gradle-user-home 'C:/Users/SHIRA/AppData/Local/Temp/hey-mike/proactive-tasks/validation/gradle-home' --init-script 'C:/Users/SHIRA/AppData/Local/Temp/hey-mike/task-board-v2/validation/storage.gradle' :core:test :workspace:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' *> (Join-Path $PSScriptRoot 'build.log')
$result = $LASTEXITCODE
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build.log') -Tail 55
exit $result
