# WebView release probe

This separate app uses `:web` directly. It is non-debuggable and signed with the
local debug key for testing. It does not replace or instrument Hey Mike.

From the repository root, set ANDROID_HOME and build:

```powershell
.\gradlew.bat -p tools/browser-probe assembleRelease --no-daemon
```

Install `tools/browser-probe/build/outputs/apk/release/Mike Browser Probe-release.apk`
using the approved device tools. Open **Mike Browser Probe**. It checks its own
UID's LocalSocket/CDP, DOM fill-fill-touch plan, trusted click, PNG screenshot,
and HTTPS navigation. It writes its result to its external files directory as
`probe.txt` and a fixture screenshot as `probe.png`.

It enables WebView debugging intentionally, only during the probe. This test
does not prove the floating window host, user takeover, production accounts,
approval cards, or navigation/resource policies on the device.
