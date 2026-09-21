# Getting started

Hey Mike is a Developer Preview. Use it for local testing and keep a
recovery path for the phone you connect.

## Requirements

- Windows, PowerShell, Git, JDK 17, and Python 3.
- Android SDK platform tools and an Android 11+ device (`minSdk 30`).
- A supported ARM64 Android phone for the practical runtime path today.
- A Codex account for sign-in.

The project also packages an x86_64 runtime for emulator builds. The current
x86_64 app-server path exits with `SIGSYS` (159), so an emulator install does
not prove that chat or device control works.

## Build

```powershell
git clone https://github.com/Yoni-Raich/hey-mike.git
cd hey-mike
.\gradlew.bat :app:assembleDevDebug --no-daemon
```

The `preBuild` step runs `tools/prepare_runtime.py`. If the pinned runtime is
not cached, it downloads the official Codex app-server packages and a pinned CA
bundle, checks their hashes, and stages them for the APK. Do not put credentials
in the repository or in build logs.

The dev package is `dev.androidagent.app.dev`. The prod flavor is
`dev.androidagent.app`; a prod flavor build is not automatically a
production-signed release.

## Install and launch

```powershell
adb devices -l
adb -s <serial> install -r app\build\outputs\apk\dev\debug\app-dev-debug.apk
adb -s <serial> shell am start -n dev.androidagent.app.dev/.MainActivity
```

Choose the intended `<serial>` before every device operation. Do not use a
device reported as `unauthorized` or `offline`. If more than one device is
connected, never rely on the default ADB target.

On first launch, sign in with the Codex account. The app keeps its runtime,
configuration, sessions, and artifacts in app-private storage. The APK does
not contain an API key.

## Optional setup

- Turn on Wireless Debugging and complete pairing if you want the ADB backend.
- Start the app before enabling its Accessibility service. Android may leave a
  service enabled against a cold package in a crashed state.
- Grant overlay permission if you want the floating control card.
- Grant microphone permission only if you want realtime voice.
- Allow notifications if you want the foreground-service notification.

The app can still use the in-process Accessibility backend without Wireless
Debugging, but its real phone behavior is not fully validated yet. Read
[Permissions and privacy](PERMISSIONS_AND_PRIVACY.md) before enabling these
capabilities.

## After setup

Run the checks in [Testing](TESTING.md), then review
[Known issues](KNOWN_ISSUES.md). A successful build or install is only evidence
for that build or install; it is not evidence of a complete signed-in phone
conversation.
