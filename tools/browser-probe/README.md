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

## Production host scenarios

`DeepProbeActivity` uses `AndroidBrowserHost`, its parked overlay WebView,
`BrowserFrame`, native touch input and the normal tool gateway. Allow the
probe's **Display over other apps** permission through Android Settings once.
It never grants approvals: its confirmation callback always returns false.

Select the connected test phone with `adb devices -l`, then use its serial:

```powershell
adb -s <serial> shell am start -S -n dev.androidagent.browserprobe/.DeepProbeActivity --es scenario keyboard
```

Scenarios are `forms` (six real httpbin form runs), `keyboard` (four frame
heights, two repeats each), `sites` (Wikipedia, MDN, GitHub and an external
WHATWG link, two repeats), and `controls` (real cover, user takeover, Stop,
24-step plan and two chat pages). Forms are never submitted. `controls`
replaces the probe's own example.com document with a controlled fixture.
The public-site scenarios use the sites' live DOM and snapshot refs.

Each launch replaces `deep.jsonl` under the probe's external files directory.
Copy it before another launch. `FINISHED` includes a failure count; a `FATAL`
row is incomplete, even if earlier cases passed. Layout diagnostics include
public form values and HTML, so keep the files under ignored `artifacts/`.
Geometry reads are diagnostic and do not click or dismiss anything.

These scenarios prove the production browser module on the selected phone.
They do not include a model conversation, the Compose chat screen, account
login, human input, approval UI or the hosting phone. Test those separately.
